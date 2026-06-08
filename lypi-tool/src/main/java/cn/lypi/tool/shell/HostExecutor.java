package cn.lypi.tool.shell;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.runtime.ExecutionMetadata;
import cn.lypi.contracts.runtime.ExecutionRequest;
import cn.lypi.contracts.runtime.ExecutionResult;
import cn.lypi.contracts.runtime.Executor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 在宿主机上执行一次命令。
 *
 * NOTE: 该执行器不提供真实沙盒隔离，只负责本地进程生命周期、输出采集和基础环境净化。
 */
public final class HostExecutor implements Executor {
    private static final int ABORT_EXIT_CODE = 130;
    private static final int TIMEOUT_EXIT_CODE = 124;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration TERMINATION_GRACE = Duration.ofMillis(500);
    private static final Duration INITIAL_OUTPUT_DRAIN_GRACE = Duration.ofMillis(100);
    private static final Duration FINAL_OUTPUT_DRAIN_GRACE = Duration.ofSeconds(1);
    private static final Set<String> SENSITIVE_ENV_KEYS = Set.of(
        "ANTHROPIC_API_KEY",
        "CLAUDE_CODE_OAUTH_TOKEN",
        "ANTHROPIC_AUTH_TOKEN",
        "ANTHROPIC_FOUNDRY_API_KEY",
        "ANTHROPIC_CUSTOM_HEADERS",
        "OPENAI_API_KEY",
        "AZURE_OPENAI_API_KEY",
        "OTEL_EXPORTER_OTLP_HEADERS",
        "OTEL_EXPORTER_OTLP_LOGS_HEADERS",
        "OTEL_EXPORTER_OTLP_METRICS_HEADERS",
        "OTEL_EXPORTER_OTLP_TRACES_HEADERS",
        "AWS_SECRET_ACCESS_KEY",
        "AWS_SESSION_TOKEN",
        "AWS_BEARER_TOKEN_BEDROCK",
        "GOOGLE_APPLICATION_CREDENTIALS",
        "AZURE_CLIENT_SECRET",
        "AZURE_CLIENT_CERTIFICATE_PATH",
        "ACTIONS_ID_TOKEN_REQUEST_TOKEN",
        "ACTIONS_ID_TOKEN_REQUEST_URL",
        "ACTIONS_RUNTIME_TOKEN",
        "ACTIONS_RUNTIME_URL",
        "ALL_INPUTS",
        "OVERRIDE_GITHUB_TOKEN",
        "DEFAULT_WORKFLOW_TOKEN",
        "SSH_SIGNING_KEY"
    );

    private final Map<String, String> parentEnv;

    public HostExecutor() {
        this(System.getenv());
    }

    HostExecutor(Map<String, String> parentEnv) {
        this.parentEnv = Map.copyOf(Objects.requireNonNull(parentEnv, "parentEnv must not be null"));
    }

    @Override
    public String name() {
        return "host";
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request, ProgressSink progress, AbortSignal signal) {
        Objects.requireNonNull(request, "request must not be null");
        ProgressSink safeProgress = progress == null ? ignored -> {
        } : progress;
        AbortSignal safeSignal = signal == null ? () -> false : signal;
        if (safeSignal.aborted()) {
            return new ExecutionResult(
                ABORT_EXIT_CODE,
                "",
                "command aborted before start",
                false,
                Optional.empty(),
                ExecutionMetadata.unsandboxed(name())
            );
        }

        Process process;
        try {
            process = startProcess(request);
        } catch (IOException exception) {
            throw new IllegalStateException("无法启动命令: " + exception.getMessage(), exception);
        }
        closeChildInput(process);

        OutputCollector stdout = new OutputCollector(process.getInputStream(), "stdout", safeProgress);
        OutputCollector stderr = new OutputCollector(process.getErrorStream(), "stderr", safeProgress);
        Thread stdoutThread = startCollector(stdout, "lypi-host-executor-stdout");
        Thread stderrThread = startCollector(stderr, "lypi-host-executor-stderr");

        WaitResult waitResult = waitForProcess(process, timeout(request), safeSignal);
        if (!waitResult.timedOut() && !waitResult.aborted()) {
            waitForCollectors(INITIAL_OUTPUT_DRAIN_GRACE, stdoutThread, stderrThread);
            terminateDescendants(process);
            waitForCollectors(FINAL_OUTPUT_DRAIN_GRACE, stdoutThread, stderrThread);
        } else {
            closeCollectors(stdout, stderr);
            waitForCollectors(FINAL_OUTPUT_DRAIN_GRACE, stdoutThread, stderrThread);
        }

        int exitCode = waitResult.exitCode();
        if (waitResult.timedOut()) {
            exitCode = exitCode == 0 ? TIMEOUT_EXIT_CODE : exitCode;
        } else if (waitResult.aborted()) {
            exitCode = exitCode == 0 ? ABORT_EXIT_CODE : exitCode;
        }
        return new ExecutionResult(
            exitCode,
            stdout.output(),
            stderr.output(),
            waitResult.timedOut(),
            Optional.empty(),
            ExecutionMetadata.unsandboxed(name())
        );
    }

    private Process startProcess(ExecutionRequest request) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(request.command());
        if (request.cwd() != null) {
            builder.directory(request.cwd().toFile());
        }
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.putAll(scrubbedParentEnv());
        if (request.env() != null) {
            environment.putAll(request.env());
        }
        scrubSensitiveKeys(environment);
        environment.putIfAbsent("GIT_EDITOR", "true");
        environment.putIfAbsent("LYPI", "1");
        return builder.start();
    }

    private void closeChildInput(Process process) {
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // 子进程可能已经退出并关闭 stdin；执行结果以后续进程状态和输出采集为准。
        }
    }

    private Map<String, String> scrubbedParentEnv() {
        java.util.LinkedHashMap<String, String> env = new java.util.LinkedHashMap<>(parentEnv);
        scrubSensitiveKeys(env);
        return env;
    }

    private void scrubSensitiveKeys(Map<String, String> env) {
        for (String key : sensitiveKeysFor(env)) {
            env.remove(key);
        }
    }

    private Set<String> sensitiveKeysFor(Map<String, String> env) {
        Set<String> keys = new HashSet<>();
        for (String key : env.keySet()) {
            if (SENSITIVE_ENV_KEYS.contains(key) || key.startsWith("INPUT_") && SENSITIVE_ENV_KEYS.contains(key.substring("INPUT_".length()))) {
                keys.add(key);
            }
        }
        return keys;
    }

    private Duration timeout(ExecutionRequest request) {
        Duration timeout = request.timeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return DEFAULT_TIMEOUT;
        }
        return timeout;
    }

    private Thread startCollector(OutputCollector collector, String name) {
        Thread thread = Thread.ofVirtual().name(name).unstarted(collector);
        thread.start();
        return thread;
    }

    private WaitResult waitForProcess(Process process, Duration timeout, AbortSignal signal) {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (true) {
            if (signal.aborted()) {
                terminate(process);
                return new WaitResult(waitForExit(process, ABORT_EXIT_CODE), false, true);
            }
            try {
                if (process.waitFor(25, TimeUnit.MILLISECONDS)) {
                    return new WaitResult(process.exitValue(), false, false);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                terminate(process);
                return new WaitResult(waitForExit(process, ABORT_EXIT_CODE), false, true);
            }
            if (System.nanoTime() >= deadlineNanos) {
                terminate(process);
                return new WaitResult(waitForExit(process, TIMEOUT_EXIT_CODE), true, false);
            }
        }
    }

    private void terminate(Process process) {
        destroyTree(process, false);
        try {
            if (!process.waitFor(TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                destroyTree(process, true);
                process.waitFor(TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            destroyTree(process, true);
        }
    }

    private void terminateDescendants(Process process) {
        process.toHandle().descendants().forEach(descendant -> destroy(descendant, false));
        try {
            TimeUnit.MILLISECONDS.sleep(TERMINATION_GRACE.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        process.toHandle().descendants().forEach(descendant -> destroy(descendant, true));
    }

    private void destroyTree(Process process, boolean forcibly) {
        ProcessHandle handle = process.toHandle();
        handle.descendants().forEach(descendant -> destroy(descendant, forcibly));
        destroy(handle, forcibly);
    }

    private void destroy(ProcessHandle handle, boolean forcibly) {
        if (!handle.isAlive()) {
            return;
        }
        if (forcibly) {
            handle.destroyForcibly();
            return;
        }
        handle.destroy();
    }

    private int waitForExit(Process process, int fallbackExitCode) {
        try {
            if (process.waitFor(TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                return process.exitValue();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        return fallbackExitCode;
    }

    private void waitForCollectors(Duration duration, Thread... threads) {
        long deadlineNanos = System.nanoTime() + duration.toNanos();
        for (Thread thread : threads) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0 || !thread.isAlive()) {
                continue;
            }
            try {
                thread.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void closeCollectors(OutputCollector... collectors) {
        for (OutputCollector collector : collectors) {
            collector.close();
        }
    }

    private record WaitResult(int exitCode, boolean timedOut, boolean aborted) {
    }

    private static final class OutputCollector implements Runnable {
        private final InputStream input;
        private final String stream;
        private final ProgressSink progress;
        private final StringBuilder output = new StringBuilder();

        private OutputCollector(InputStream input, String stream, ProgressSink progress) {
            this.input = input;
            this.stream = stream;
            this.progress = progress;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[4096];
            int read;
            try {
                while ((read = input.read(buffer)) != -1) {
                    String delta = new String(buffer, 0, read, StandardCharsets.UTF_8);
                    synchronized (output) {
                        output.append(delta);
                    }
                    progress.progress(ToolProgress.output(stream, delta));
                }
            } catch (IOException ignored) {
                // 进程被终止时流可能提前关闭；最终结果以已采集输出为准。
            }
        }

        private String output() {
            synchronized (output) {
                return output.toString();
            }
        }

        private void close() {
            try {
                input.close();
            } catch (IOException ignored) {
                // 流可能已随进程退出关闭。
            }
        }
    }
}
