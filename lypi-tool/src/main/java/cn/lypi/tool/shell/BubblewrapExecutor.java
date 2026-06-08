package cn.lypi.tool.shell;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.common.ProgressSink;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

    private final BubblewrapCommandBuilder commandBuilder;
    private final Executor hostExecutor;
    private final Supplier<Optional<Path>> bwrapPathSupplier;
    private final Map<NetworkMode, PreflightResult> preflightResults = new EnumMap<>(NetworkMode.class);

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
        PreflightResult preflight = preflight(bwrapPath.orElseThrow(), networkMode(request));
        if (!preflight.available()) {
            return handleUnavailable(request, progress, signal, preflight.diagnostic());
        }
        List<String> argv = commandBuilder.build(request);
        java.util.ArrayList<String> executableArgv = new java.util.ArrayList<>(argv);
        executableArgv.set(0, bwrapPath.orElseThrow().toString());
        ExecutionRequest bwrapRequest = new ExecutionRequest(
            List.copyOf(executableArgv),
            request.cwd(),
            request.env(),
            timeout(request),
            request.sandboxPolicy()
        );
        ExecutionResult result = hostExecutor.execute(bwrapRequest, progress, signal);
        if (isBubblewrapStartupFailure(result)) {
            return result.withMetadata(ExecutionMetadata.unsandboxed(name(), "bubblewrap execution failed"));
        }
        return result.withMetadata(ExecutionMetadata.sandboxed(name()));
    }

    private ExecutionResult handleUnavailable(ExecutionRequest request, ProgressSink progress, AbortSignal signal, String diagnostic) {
        SandboxRuntimePolicy policy = request.sandboxPolicy();
        if (policy != null && policy.failIfUnavailable()) {
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

    private Duration timeout(ExecutionRequest request) {
        return request.timeout();
    }

    private NetworkMode networkMode(ExecutionRequest request) {
        SandboxRuntimePolicy policy = request.sandboxPolicy();
        return policy == null ? NetworkMode.DISABLED : policy.networkMode();
    }

    private synchronized PreflightResult preflight(Path bwrapPath, NetworkMode networkMode) {
        return preflightResults.computeIfAbsent(networkMode, mode -> runPreflight(bwrapPath, mode));
    }

    private PreflightResult runPreflight(Path bwrapPath, NetworkMode networkMode) {
        List<String> command = preflightCommand(bwrapPath, networkMode);
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

    private List<String> preflightCommand(Path bwrapPath, NetworkMode networkMode) {
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
        command.add("--proc");
        command.add("/proc");
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

    private boolean isBubblewrapStartupFailure(ExecutionResult result) {
        return result.exitCode() == 1
            && result.stdout().isBlank()
            && result.stderr().startsWith("bwrap:");
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

    private record PreflightResult(boolean available, String diagnostic) {
        private static PreflightResult ok() {
            return new PreflightResult(true, "");
        }

        private static PreflightResult unavailable(String diagnostic) {
            return new PreflightResult(false, diagnostic);
        }
    }
}
