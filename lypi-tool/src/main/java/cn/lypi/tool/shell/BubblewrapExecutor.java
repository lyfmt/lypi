package cn.lypi.tool.shell;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.common.ToolProgressKind;
import cn.lypi.contracts.runtime.ExecutionMetadata;
import cn.lypi.contracts.runtime.ExecutionRequest;
import cn.lypi.contracts.runtime.ExecutionResult;
import cn.lypi.contracts.runtime.Executor;
import cn.lypi.contracts.runtime.NetworkMode;
import cn.lypi.contracts.runtime.SandboxRuntimePolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 使用系统 Bubblewrap 执行命令。
 *
 * NOTE: 第一版依赖系统 bwrap；不可用时按 SandboxRuntimePolicy 决定失败或回退宿主执行器。
 */
public final class BubblewrapExecutor implements Executor {
    private static final int POLICY_VIOLATION_EXIT_CODE = 1;
    private static final int UNAVAILABLE_EXIT_CODE = 127;
    private static final int POLICY_REJECTED_EXIT_CODE = 126;
    private static final String UNAVAILABLE_MESSAGE = "bubblewrap unavailable";
    private static final String SANDBOX_STARTED_SENTINEL_PREFIX = "__LYPI_BWRAP_STARTED__";
    private static final ProtectedCreateCoordinator PROTECTED_CREATE_COORDINATOR = new ProtectedCreateCoordinator();

    private final BubblewrapCommandBuilder commandBuilder;
    private final Executor hostExecutor;
    private final Supplier<Optional<Path>> bwrapPathSupplier;
    private final Map<PreflightKey, PreflightResult> preflightResults = new LinkedHashMap<>();

    public BubblewrapExecutor() {
        this(BubblewrapCommandBuilder.defaults(), new HostExecutor(), BubblewrapExecutor::findSystemBwrap);
    }

    public BubblewrapExecutor(Executor hostExecutor) {
        this(BubblewrapCommandBuilder.defaults(), hostExecutor, BubblewrapExecutor::findSystemBwrap);
    }

    BubblewrapExecutor(
        BubblewrapCommandBuilder commandBuilder,
        Executor hostExecutor,
        Supplier<Optional<Path>> bwrapPathSupplier
    ) {
        this.commandBuilder = Objects.requireNonNull(commandBuilder, "commandBuilder must not be null");
        this.hostExecutor = Objects.requireNonNull(hostExecutor, "hostExecutor must not be null");
        this.bwrapPathSupplier = Objects.requireNonNull(bwrapPathSupplier, "bwrapPathSupplier must not be null");
    }

    @Override
    public String name() {
        return "bubblewrap";
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request, ProgressSink progress, AbortSignal signal) {
        Objects.requireNonNull(request, "request must not be null");
        Optional<Path> bwrapPath = bwrapPathSupplier.get();
        if (bwrapPath.isEmpty()) {
            return handleUnavailable(request, progress, signal, UNAVAILABLE_MESSAGE);
        }
        PreflightResult preflight = preflight(bwrapPath.orElseThrow(), networkMode(request), true);
        boolean mountProc = true;
        if (!preflight.available() && isProcMountFailure(preflight.diagnostic())) {
            PreflightResult noProcPreflight = preflight(bwrapPath.orElseThrow(), networkMode(request), false);
            if (noProcPreflight.available()) {
                preflight = noProcPreflight;
                mountProc = false;
            } else {
                preflight = noProcPreflight;
            }
        }
        if (!preflight.available()) {
            return handleUnavailable(request, progress, signal, preflight.diagnostic());
        }
        String sandboxStartedSentinel = sandboxStartedSentinel();
        ExecutionRequest wrappedRequest = requestWithSandboxStartedSentinel(request, sandboxStartedSentinel);
        BubblewrapCommandBuilder.BuildResult buildResult;
        try {
            buildResult = commandBuilder.buildDetailed(
                wrappedRequest,
                new BubblewrapCommandBuilder.Options(mountProc)
            );
        } catch (IllegalArgumentException exception) {
            return policyRejected(exception);
        }
        java.util.ArrayList<String> executableArgv = new java.util.ArrayList<>(buildResult.argv());
        executableArgv.set(0, bwrapPath.orElseThrow().toString());
        ExecutionRequest bwrapRequest = new ExecutionRequest(
            List.copyOf(executableArgv),
            request.cwd(),
            request.env(),
            timeout(request),
            request.sandboxPolicy()
        );
        ProtectedCreateLease protectedCreateLease = PROTECTED_CREATE_COORDINATOR.acquire(buildResult.protectedCreateTargets());
        ProtectedCreateMonitor protectedCreateMonitor = startProtectedCreateMonitor(protectedCreateLease.paths());
        ExecutionResult result;
        ProtectedCreateCleanupResult protectedCreateCleanup = ProtectedCreateCleanupResult.empty();
        try {
            try {
                result = hostExecutor.execute(
                    bwrapRequest,
                    progressWithoutSandboxStartedSentinel(progress, sandboxStartedSentinel),
                    signal
                );
            } finally {
                protectedCreateCleanup = protectedCreateMonitor.stop();
                cleanupSyntheticMountTargets(buildResult.syntheticMountTargets());
                protectedCreateCleanup = protectedCreateCleanup.merge(cleanupProtectedCreateTargets(protectedCreateLease.paths()));
            }
        } finally {
            protectedCreateLease.close();
        }
        if (!sandboxStarted(result, sandboxStartedSentinel)) {
            return handleExecutionFailure(request, progress, signal, result);
        }
        return withProtectedCreateViolation(withoutSandboxStartedSentinel(result, sandboxStartedSentinel), protectedCreateCleanup)
            .withMetadata(ExecutionMetadata.sandboxed(name()));
    }

    private ExecutionResult policyRejected(IllegalArgumentException exception) {
        String diagnostic = "bubblewrap policy rejected: " + exception.getMessage();
        return new ExecutionResult(
            POLICY_REJECTED_EXIT_CODE,
            "",
            diagnostic,
            false,
            Optional.empty(),
            ExecutionMetadata.unsandboxed(name(), diagnostic)
        );
    }

    private void cleanupSyntheticMountTargets(List<BubblewrapCommandBuilder.SyntheticMountTarget> syntheticMountTargets) {
        syntheticMountTargets.stream()
            .sorted((left, right) -> Integer.compare(right.path().getNameCount(), left.path().getNameCount()))
            .forEach(this::deleteSyntheticMountTargetIfSafe);
    }

    private void deleteSyntheticMountTargetIfSafe(BubblewrapCommandBuilder.SyntheticMountTarget target) {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(target.path(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException ignored) {
            // NOTE: 路径已消失、被替换或无法安全确认时，保守保留。
            return;
        }
        if (!target.shouldRemoveAfter(attributes)) {
            return;
        }
        switch (target.kind()) {
            case EMPTY_FILE -> deleteEmptyFileIfSafe(target.path());
            case EMPTY_DIRECTORY -> deleteEmptyDirectoryIfSafe(target.path());
        }
    }

    private void deleteEmptyFileIfSafe(Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isRegularFile() && attributes.size() == 0) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // NOTE: 路径已消失、被替换或无法安全确认为空文件时，保守保留。
        }
    }

    private void deleteEmptyDirectoryIfSafe(Path path) {
        try {
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                Files.deleteIfExists(path);
            }
        } catch (DirectoryNotEmptyException ignored) {
            // NOTE: 非空目录可能已有宿主数据，必须保留。
        } catch (IOException ignored) {
            // NOTE: 路径已消失、被替换或无法安全确认为空目录时，保守保留。
        }
    }

    private ProtectedCreateMonitor startProtectedCreateMonitor(List<Path> paths) {
        ProtectedCreateMonitor monitor = new ProtectedCreateMonitor(paths);
        monitor.start();
        return monitor;
    }

    private ProtectedCreateCleanupResult cleanupProtectedCreateTargets(List<Path> paths) {
        ProtectedCreateCleanupAccumulator cleanup = new ProtectedCreateCleanupAccumulator();
        List<Path> sorted = paths.stream()
            .sorted((left, right) -> Integer.compare(right.getNameCount(), left.getNameCount()))
            .toList();
        for (Path path : sorted) {
            cleanup.add(removeProtectedCreateTarget(path));
        }
        return cleanup.toResult();
    }

    private ProtectedCreateRemoval removeProtectedCreateTarget(Path path) {
        try {
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException exception) {
                return ProtectedCreateRemoval.none();
            }
            if (attributes.isDirectory()) {
                deleteDirectoryTree(path);
            } else {
                Files.deleteIfExists(path);
            }
            return ProtectedCreateRemoval.violation(path);
        } catch (IOException exception) {
            return ProtectedCreateRemoval.failure(path, exception);
        } catch (RuntimeException exception) {
            return ProtectedCreateRemoval.failure(path, exception);
        }
    }

    private void deleteDirectoryTree(Path path) throws IOException {
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                throw exception;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private ExecutionResult withProtectedCreateViolation(ExecutionResult result, ProtectedCreateCleanupResult cleanup) {
        if (!cleanup.hasViolation()) {
            return result;
        }
        String stderr = result.stderr();
        for (Path violation : cleanup.violations()) {
            stderr = appendDiagnosticLine(stderr, "sandbox blocked creation of protected workspace metadata path " + violation);
        }
        for (String failure : cleanup.failures()) {
            stderr = appendDiagnosticLine(stderr, failure);
        }
        int exitCode = result.exitCode() == 0 && !result.timedOut() ? POLICY_VIOLATION_EXIT_CODE : result.exitCode();
        return new ExecutionResult(
            exitCode,
            result.stdout(),
            stderr,
            result.timedOut(),
            result.persistedOutput(),
            result.metadata()
        );
    }

    private String appendDiagnosticLine(String stderr, String diagnostic) {
        if (stderr == null || stderr.isEmpty()) {
            return diagnostic;
        }
        if (stderr.endsWith("\n")) {
            return stderr + diagnostic;
        }
        return stderr + "\n" + diagnostic;
    }

    private final class ProtectedCreateMonitor {
        private final List<Path> paths;
        private final AtomicBoolean stop = new AtomicBoolean();
        private final ProtectedCreateCleanupAccumulator cleanup = new ProtectedCreateCleanupAccumulator();
        private ProtectedCreateWatcher watcher;
        private Thread thread;

        private ProtectedCreateMonitor(List<Path> paths) {
            this.paths = List.copyOf(paths);
        }

        private void start() {
            if (paths.isEmpty()) {
                return;
            }
            watcher = ProtectedCreateWatcher.open(paths);
            thread = Thread.ofVirtual()
                .name("lypi-bwrap-protected-create-monitor")
                .unstarted(this::run);
            thread.start();
        }

        private void run() {
            while (!stop.get()) {
                for (Path path : paths) {
                    cleanup.add(removeProtectedCreateTarget(path));
                }
                if (watcher != null) {
                    cleanup.addAll(watcher.pollCreatedTargets(stop));
                    continue;
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(1);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private ProtectedCreateCleanupResult stop() {
            if (thread == null) {
                return ProtectedCreateCleanupResult.empty();
            }
            stop.set(true);
            boolean interrupted = false;
            while (thread.isAlive()) {
                try {
                    thread.join();
                } catch (InterruptedException exception) {
                    interrupted = true;
                    stop.set(true);
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            if (watcher != null) {
                cleanup.addAll(watcher.drainCreatedTargets());
                watcher.close();
            }
            return cleanup.toResult();
        }
    }

    private static final class ProtectedCreateWatcher implements AutoCloseable {
        private final WatchService watchService;
        private final Map<WatchKey, Path> watchedParents;
        private final Map<Path, LinkedHashSet<String>> targetNamesByParent;

        private ProtectedCreateWatcher(
            WatchService watchService,
            Map<WatchKey, Path> watchedParents,
            Map<Path, LinkedHashSet<String>> targetNamesByParent
        ) {
            this.watchService = watchService;
            this.watchedParents = Map.copyOf(watchedParents);
            this.targetNamesByParent = copyTargetNames(targetNamesByParent);
        }

        private static ProtectedCreateWatcher open(List<Path> paths) {
            Map<Path, LinkedHashSet<String>> targetNamesByParent = new LinkedHashMap<>();
            for (Path path : paths) {
                Path parent = path.getParent();
                Path fileName = path.getFileName();
                if (parent == null || fileName == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                targetNamesByParent
                    .computeIfAbsent(parent, ignored -> new LinkedHashSet<>())
                    .add(fileName.toString());
            }
            if (targetNamesByParent.isEmpty()) {
                return null;
            }
            WatchService watchService = null;
            try {
                watchService = FileSystems.getDefault().newWatchService();
                Map<WatchKey, Path> watchedParents = new LinkedHashMap<>();
                for (Path parent : targetNamesByParent.keySet()) {
                    WatchKey key = parent.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);
                    watchedParents.put(key, parent);
                }
                if (watchedParents.isEmpty()) {
                    watchService.close();
                    return null;
                }
                return new ProtectedCreateWatcher(watchService, watchedParents, targetNamesByParent);
            } catch (IOException exception) {
                if (watchService != null) {
                    try {
                        watchService.close();
                    } catch (IOException ignored) {
                        // NOTE: falling back to polling; leaked close failures do not affect command result.
                    }
                }
                return null;
            }
        }

        private static Map<Path, LinkedHashSet<String>> copyTargetNames(Map<Path, LinkedHashSet<String>> source) {
            Map<Path, LinkedHashSet<String>> copy = new LinkedHashMap<>();
            for (Map.Entry<Path, LinkedHashSet<String>> entry : source.entrySet()) {
                copy.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
            }
            return Map.copyOf(copy);
        }

        private ProtectedCreateCleanupResult pollCreatedTargets(AtomicBoolean stop) {
            ProtectedCreateCleanupAccumulator cleanup = new ProtectedCreateCleanupAccumulator();
            try {
                WatchKey key = watchService.poll(10, TimeUnit.MILLISECONDS);
                while (key != null) {
                    collectCreatedTargets(cleanup, key);
                    key = watchService.poll();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                stop.set(true);
            } catch (ClosedWatchServiceException ignored) {
                stop.set(true);
            }
            return cleanup.toResult();
        }

        private ProtectedCreateCleanupResult drainCreatedTargets() {
            ProtectedCreateCleanupAccumulator cleanup = new ProtectedCreateCleanupAccumulator();
            WatchKey key = watchService.poll();
            while (key != null) {
                collectCreatedTargets(cleanup, key);
                key = watchService.poll();
            }
            return cleanup.toResult();
        }

        private void collectCreatedTargets(ProtectedCreateCleanupAccumulator cleanup, WatchKey key) {
            Path parent = watchedParents.get(key);
            if (parent == null) {
                key.reset();
                return;
            }
            LinkedHashSet<String> targetNames = targetNamesByParent.get(parent);
            if (targetNames == null || targetNames.isEmpty()) {
                key.reset();
                return;
            }
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    for (String targetName : targetNames) {
                        cleanup.add(ProtectedCreateRemoval.violation(parent.resolve(targetName).normalize()));
                    }
                    continue;
                }
                Object context = event.context();
                String createdName = context instanceof Path path ? path.toString() : String.valueOf(context);
                if (targetNames.contains(createdName)) {
                    cleanup.add(ProtectedCreateRemoval.violation(parent.resolve(createdName).normalize()));
                }
            }
            key.reset();
        }

        @Override
        public void close() {
            try {
                watchService.close();
            } catch (IOException ignored) {
                // NOTE: monitor stop path; final cleanup still determines the visible result.
            }
        }
    }

    private ExecutionResult handleUnavailable(ExecutionRequest request, ProgressSink progress, AbortSignal signal, String diagnostic) {
        SandboxRuntimePolicy policy = request.sandboxPolicy();
        if (requiresSandbox(policy)) {
            return new ExecutionResult(
                UNAVAILABLE_EXIT_CODE,
                "",
                diagnostic,
                false,
                Optional.empty(),
                ExecutionMetadata.unsandboxed(name(), diagnostic)
            );
        }
        return hostExecutor.execute(request, progress, signal)
            .withMetadata(ExecutionMetadata.unsandboxed(hostExecutor.name(), diagnostic + "; fell back to host"));
    }

    private ExecutionResult handleExecutionFailure(
        ExecutionRequest request,
        ProgressSink progress,
        AbortSignal signal,
        ExecutionResult result
    ) {
        if (signal != null && signal.aborted()) {
            return result.withMetadata(ExecutionMetadata.unsandboxed(name(), "bubblewrap execution aborted"));
        }
        if (result.timedOut()) {
            return result.withMetadata(ExecutionMetadata.unsandboxed(name(), "bubblewrap execution timed out"));
        }
        SandboxRuntimePolicy policy = request.sandboxPolicy();
        if (requiresSandbox(policy)) {
            return result.withMetadata(ExecutionMetadata.unsandboxed(name(), "bubblewrap execution failed"));
        }
        return hostExecutor.execute(request, progress, signal)
            .withMetadata(ExecutionMetadata.unsandboxed(hostExecutor.name(), "bubblewrap execution failed; fell back to host"));
    }

    private boolean requiresSandbox(SandboxRuntimePolicy policy) {
        return policy != null
            && (policy.failIfUnavailable() || !policy.denyRead().isEmpty() || !policy.denyWrite().isEmpty());
    }

    private Duration timeout(ExecutionRequest request) {
        return request.timeout();
    }

    private NetworkMode networkMode(ExecutionRequest request) {
        SandboxRuntimePolicy policy = request.sandboxPolicy();
        return policy == null ? NetworkMode.DISABLED : policy.networkMode();
    }

    private synchronized PreflightResult preflight(Path bwrapPath, NetworkMode networkMode, boolean mountProc) {
        return preflightResults.computeIfAbsent(
            new PreflightKey(networkMode, mountProc),
            key -> runPreflight(bwrapPath, key.networkMode(), key.mountProc())
        );
    }

    private PreflightResult runPreflight(Path bwrapPath, NetworkMode networkMode, boolean mountProc) {
        List<String> command = preflightCommand(bwrapPath, networkMode, mountProc);
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException exception) {
            return PreflightResult.unavailable(UNAVAILABLE_MESSAGE + ": " + exception.getMessage());
        }
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return PreflightResult.unavailable(UNAVAILABLE_MESSAGE + ": preflight timed out");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                String suffix = output.isBlank() ? "" : ": " + output;
                return PreflightResult.unavailable(UNAVAILABLE_MESSAGE + ": preflight failed exit " + process.exitValue() + suffix);
            }
            return PreflightResult.ok();
        } catch (IOException exception) {
            return PreflightResult.unavailable(UNAVAILABLE_MESSAGE + ": " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return PreflightResult.unavailable(UNAVAILABLE_MESSAGE + ": preflight interrupted");
        }
    }

    private List<String> preflightCommand(Path bwrapPath, NetworkMode networkMode, boolean mountProc) {
        List<String> command = new ArrayList<>();
        command.add(bwrapPath.toString());
        command.add("--new-session");
        command.add("--die-with-parent");
        command.add("--unshare-user");
        command.add("--unshare-pid");
        if (networkMode == NetworkMode.DISABLED) {
            command.add("--unshare-net");
        }
        command.add("--ro-bind-try");
        command.add("/usr");
        command.add("/usr");
        command.add("--ro-bind-try");
        command.add("/bin");
        command.add("/bin");
        command.add("--ro-bind-try");
        command.add("/lib");
        command.add("/lib");
        command.add("--ro-bind-try");
        command.add("/lib64");
        command.add("/lib64");
        command.add("--ro-bind-try");
        command.add("/etc");
        command.add("/etc");
        command.add("--dev");
        command.add("/dev");
        if (mountProc) {
            command.add("--proc");
            command.add("/proc");
        }
        command.add("--tmpfs");
        command.add("/tmp");
        command.add("--");
        command.add(trueExecutable());
        return List.copyOf(command);
    }

    private String trueExecutable() {
        if (Files.isExecutable(Path.of("/usr/bin/true"))) {
            return "/usr/bin/true";
        }
        if (Files.isExecutable(Path.of("/bin/true"))) {
            return "/bin/true";
        }
        return "true";
    }

    private boolean isProcMountFailure(String diagnostic) {
        String normalized = diagnostic == null ? "" : diagnostic.toLowerCase(Locale.ROOT);
        return normalized.contains("mount proc")
            && (normalized.contains("operation not permitted")
                || normalized.contains("permission denied")
                || normalized.contains("invalid argument"));
    }

    private String sandboxStartedSentinel() {
        return SANDBOX_STARTED_SENTINEL_PREFIX + UUID.randomUUID();
    }

    private ExecutionRequest requestWithSandboxStartedSentinel(ExecutionRequest request, String sentinel) {
        if (request.command() == null || request.command().isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        List<String> command = new ArrayList<>();
        command.add("/bin/sh");
        command.add("-c");
        command.add("printf '%s\\n' \"$1\" >&2; shift; exec \"$@\"");
        command.add("lypi-bwrap-wrapper");
        command.add(sentinel);
        command.addAll(request.command());
        return new ExecutionRequest(List.copyOf(command), request.cwd(), request.env(), request.timeout(), request.sandboxPolicy());
    }

    private boolean sandboxStarted(ExecutionResult result, String sentinel) {
        return result.stderr().contains(sentinel);
    }

    private ProgressSink progressWithoutSandboxStartedSentinel(ProgressSink progress, String sentinel) {
        if (progress == null) {
            return null;
        }
        return toolProgress -> {
            if (toolProgress == null
                || toolProgress.kind() != ToolProgressKind.OUTPUT
                || !"stderr".equals(toolProgress.stream())
                || toolProgress.delta() == null) {
                progress.progress(toolProgress);
                return;
            }
            String delta = removeSentinelLine(toolProgress.delta(), sentinel);
            if (!delta.isEmpty()) {
                progress.progress(ToolProgress.output(toolProgress.stream(), delta));
            }
        };
    }

    private ExecutionResult withoutSandboxStartedSentinel(ExecutionResult result, String sentinel) {
        return new ExecutionResult(
            result.exitCode(),
            result.stdout(),
            removeSentinelLine(result.stderr(), sentinel),
            result.timedOut(),
            result.persistedOutput(),
            result.metadata()
        );
    }

    private String removeSentinelLine(String stderr, String sentinel) {
        int index = stderr.indexOf(sentinel);
        if (index < 0) {
            return stderr;
        }
        if (index > 0 && stderr.charAt(index - 1) != '\n') {
            return stderr;
        }
        int end = index + sentinel.length();
        if (end < stderr.length() && stderr.charAt(end) == '\r') {
            end++;
        }
        if (end < stderr.length() && stderr.charAt(end) == '\n') {
            end++;
        }
        return stderr.substring(0, index) + stderr.substring(end);
    }

    private static Optional<Path> findSystemBwrap() {
        java.util.LinkedHashSet<Path> candidates = new java.util.LinkedHashSet<>();
        String path = System.getenv("PATH");
        if (path != null && !path.isBlank()) {
            for (String directory : path.split(java.io.File.pathSeparator)) {
                if (!directory.isBlank()) {
                    candidates.add(Path.of(directory, "bwrap"));
                }
            }
        }
        candidates.add(Path.of("/usr/bin/bwrap"));
        candidates.add(Path.of("/bin/bwrap"));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private record ProtectedCreateLease(List<Path> paths, List<ProtectedCreateLockRegistration> locks) implements AutoCloseable {
        private ProtectedCreateLease {
            paths = List.copyOf(paths);
            locks = List.copyOf(locks);
        }

        @Override
        public void close() {
            for (int index = locks.size() - 1; index >= 0; index--) {
                locks.get(index).lock().unlock();
            }
            PROTECTED_CREATE_COORDINATOR.release(locks);
        }
    }

    private record ProtectedCreateLockRegistration(Path path, PathLock lock) {
    }

    private static final class ProtectedCreateCoordinator {
        private final Map<Path, PathLock> locksByPath = new LinkedHashMap<>();

        private ProtectedCreateLease acquire(List<BubblewrapCommandBuilder.ProtectedCreateTarget> targets) {
            List<Path> paths = protectedCreatePaths(targets);
            List<ProtectedCreateLockRegistration> registrations = reserveLocks(paths);
            for (ProtectedCreateLockRegistration registration : registrations) {
                registration.lock().lock();
            }
            return new ProtectedCreateLease(paths, registrations);
        }

        private List<Path> protectedCreatePaths(List<BubblewrapCommandBuilder.ProtectedCreateTarget> targets) {
            LinkedHashSet<Path> paths = new LinkedHashSet<>();
            for (BubblewrapCommandBuilder.ProtectedCreateTarget target : targets) {
                paths.add(target.path().toAbsolutePath().normalize());
            }
            List<Path> sorted = new ArrayList<>(paths);
            sorted.sort((left, right) -> left.toString().compareTo(right.toString()));
            return List.copyOf(sorted);
        }

        private synchronized List<ProtectedCreateLockRegistration> reserveLocks(List<Path> paths) {
            List<ProtectedCreateLockRegistration> registrations = new ArrayList<>();
            for (Path path : paths) {
                PathLock lock = locksByPath.computeIfAbsent(path, ignored -> new PathLock());
                lock.retain();
                registrations.add(new ProtectedCreateLockRegistration(path, lock));
            }
            return List.copyOf(registrations);
        }

        private synchronized void release(List<ProtectedCreateLockRegistration> registrations) {
            for (ProtectedCreateLockRegistration registration : registrations) {
                if (registration.lock().release()) {
                    locksByPath.remove(registration.path(), registration.lock());
                }
            }
        }
    }

    private static final class PathLock {
        private final ReentrantLock lock = new ReentrantLock();
        private int references;

        private void retain() {
            references++;
        }

        private boolean release() {
            references--;
            return references == 0;
        }

        private void lock() {
            lock.lock();
        }

        private void unlock() {
            lock.unlock();
        }
    }

    private record ProtectedCreateRemoval(Path path, boolean violation, String failure) {
        private static ProtectedCreateRemoval none() {
            return new ProtectedCreateRemoval(null, false, "");
        }

        private static ProtectedCreateRemoval violation(Path path) {
            return new ProtectedCreateRemoval(path, true, "");
        }

        private static ProtectedCreateRemoval failure(Path path, Exception exception) {
            String message = exception.getMessage();
            if (message == null || message.isBlank()) {
                message = exception.getClass().getSimpleName();
            }
            return new ProtectedCreateRemoval(
                path,
                true,
                "sandbox failed to remove protected workspace metadata path " + path + ": " + message
            );
        }
    }

    private static final class ProtectedCreateCleanupAccumulator {
        private final LinkedHashSet<Path> violations = new LinkedHashSet<>();
        private final LinkedHashSet<String> failures = new LinkedHashSet<>();

        private void add(ProtectedCreateRemoval removal) {
            if (removal == null || !removal.violation()) {
                return;
            }
            if (removal.path() != null) {
                violations.add(removal.path());
            }
            if (removal.failure() != null && !removal.failure().isBlank()) {
                failures.add(removal.failure());
            }
        }

        private void addAll(ProtectedCreateCleanupResult result) {
            violations.addAll(result.violations());
            failures.addAll(result.failures());
        }

        private ProtectedCreateCleanupResult toResult() {
            return new ProtectedCreateCleanupResult(List.copyOf(violations), List.copyOf(failures));
        }
    }

    private record ProtectedCreateCleanupResult(List<Path> violations, List<String> failures) {
        private ProtectedCreateCleanupResult {
            violations = List.copyOf(violations);
            failures = List.copyOf(failures);
        }

        private static ProtectedCreateCleanupResult empty() {
            return new ProtectedCreateCleanupResult(List.of(), List.of());
        }

        private boolean hasViolation() {
            return !violations.isEmpty() || !failures.isEmpty();
        }

        private ProtectedCreateCleanupResult merge(ProtectedCreateCleanupResult other) {
            LinkedHashSet<Path> mergedViolations = new LinkedHashSet<>(violations);
            mergedViolations.addAll(other.violations());
            LinkedHashSet<String> mergedFailures = new LinkedHashSet<>(failures);
            mergedFailures.addAll(other.failures());
            return new ProtectedCreateCleanupResult(List.copyOf(mergedViolations), List.copyOf(mergedFailures));
        }
    }

    private record PreflightKey(NetworkMode networkMode, boolean mountProc) {
    }

    private record PreflightResult(boolean available, String diagnostic) {
        private static PreflightResult ok() {
            return new PreflightResult(true, "");
        }

        private static PreflightResult unavailable(String diagnostic) {
            return new PreflightResult(false, diagnostic);
        }
    }
}
