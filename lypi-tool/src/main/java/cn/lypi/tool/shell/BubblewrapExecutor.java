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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 使用系统 Bubblewrap 执行命令。
 *
 * NOTE: 第一版依赖系统 bwrap；不可用时按 SandboxRuntimePolicy 决定失败或回退宿主执行器。
 */
public final class BubblewrapExecutor implements Executor {
    private static final int UNAVAILABLE_EXIT_CODE = 127;
    private static final String UNAVAILABLE_MESSAGE = "bubblewrap unavailable";
    private static final String SANDBOX_STARTED_SENTINEL_PREFIX = "__LYPI_BWRAP_STARTED__";

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
        BubblewrapCommandBuilder.BuildResult buildResult = commandBuilder.buildDetailed(
            wrappedRequest,
            new BubblewrapCommandBuilder.Options(mountProc)
        );
        java.util.ArrayList<String> executableArgv = new java.util.ArrayList<>(buildResult.argv());
        executableArgv.set(0, bwrapPath.orElseThrow().toString());
        ExecutionRequest bwrapRequest = new ExecutionRequest(
            List.copyOf(executableArgv),
            request.cwd(),
            request.env(),
            timeout(request),
            request.sandboxPolicy()
        );
        ExecutionResult result;
        try {
            result = hostExecutor.execute(
                bwrapRequest,
                progressWithoutSandboxStartedSentinel(progress, sandboxStartedSentinel),
                signal
            );
        } finally {
            cleanupSyntheticMountTargets(buildResult.syntheticMountTargets());
        }
        if (!sandboxStarted(result, sandboxStartedSentinel)) {
            return handleExecutionFailure(request, progress, signal, result);
        }
        return withoutSandboxStartedSentinel(result, sandboxStartedSentinel)
            .withMetadata(ExecutionMetadata.sandboxed(name()));
    }

    private void cleanupSyntheticMountTargets(List<Path> syntheticMountTargets) {
        syntheticMountTargets.stream()
            .sorted((left, right) -> Integer.compare(right.getNameCount(), left.getNameCount()))
            .forEach(this::deleteSyntheticMountTargetIfSafe);
    }

    private void deleteSyntheticMountTargetIfSafe(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 只清理空目录或合成空文件；如果路径被填充，保守保留，避免误删用户数据。
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
