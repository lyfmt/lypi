package cn.lypi.tool.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.common.ToolProgressKind;
import cn.lypi.contracts.runtime.ExecutionRequest;
import cn.lypi.contracts.runtime.ExecutionResult;
import cn.lypi.contracts.runtime.NetworkMode;
import cn.lypi.contracts.runtime.SandboxRuntimePolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostExecutorTest {
    @TempDir
    Path tempDir;

    @Test
    void executesCommandAndCapturesStdout() {
        HostExecutor executor = new HostExecutor();

        ExecutionResult result = executor.execute(request("printf hello"), progress -> {
        }, () -> false);

        assertEquals("host", executor.name());
        assertEquals(0, result.exitCode());
        assertEquals("hello", result.stdout());
        assertEquals("", result.stderr());
        assertFalse(result.timedOut());
        assertEquals(Optional.empty(), result.persistedOutput());
        assertFalse(result.metadata().sandboxed());
        assertEquals("host", result.metadata().executorName());
    }

    @Test
    void preservesNonZeroExitCodeAndStderr() {
        HostExecutor executor = new HostExecutor();

        ExecutionResult result = executor.execute(request("echo problem >&2; exit 7"), progress -> {
        }, () -> false);

        assertEquals(7, result.exitCode());
        assertTrue(result.stderr().contains("problem"));
    }

    @Test
    void executesInsideRequestedCwd() throws Exception {
        Path child = Files.createDirectory(tempDir.resolve("child"));
        HostExecutor executor = new HostExecutor();

        ExecutionResult result = executor.execute(request("pwd", child, Map.of(), Duration.ofSeconds(5)), progress -> {
        }, () -> false);

        assertEquals(0, result.exitCode());
        assertEquals(child.toRealPath().toString(), result.stdout().trim());
    }

    @Test
    void appliesRequestEnvAndScrubsSensitiveParentEnv() {
        HostExecutor executor = new HostExecutor(Map.of("ANTHROPIC_API_KEY", "secret", "VISIBLE_PARENT", "visible"));

        ExecutionResult result = executor.execute(
            request("printf '%s|%s|%s|%s' \"$LYPI_TEST_VALUE\" \"${ANTHROPIC_API_KEY-unset}\" \"${OPENAI_API_KEY-unset}\" \"$VISIBLE_PARENT\"",
                tempDir,
                Map.of("LYPI_TEST_VALUE", "from-request", "OPENAI_API_KEY", "request-secret"),
                Duration.ofSeconds(5)),
            progress -> {
            },
            () -> false
        );

        assertEquals(0, result.exitCode());
        assertEquals("from-request|unset|unset|visible", result.stdout());
    }

    @Test
    void terminatesCommandWhenTimeoutExpires() {
        HostExecutor executor = new HostExecutor();

        ExecutionResult result = executor.execute(request("sleep 5", tempDir, Map.of(), Duration.ofMillis(100)), progress -> {
        }, () -> false);

        assertTrue(result.timedOut());
        assertNotEquals(0, result.exitCode());
    }

    @Test
    void returnsWithoutStartingProcessWhenAlreadyAborted() {
        HostExecutor executor = new HostExecutor();

        ExecutionResult result = executor.execute(request("printf should-not-run"), progress -> {
        }, () -> true);

        assertNotEquals(0, result.exitCode());
        assertEquals("", result.stdout());
        assertTrue(result.stderr().contains("aborted"));
    }

    @Test
    void terminatesRunningCommandWhenAbortSignalIsRaised() throws Exception {
        HostExecutor executor = new HostExecutor();
        AtomicBoolean aborted = new AtomicBoolean(false);
        AbortSignal signal = aborted::get;

        Thread aborter = new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            aborted.set(true);
        });
        aborter.start();

        ExecutionResult result = executor.execute(request("sleep 5"), progress -> {
        }, signal);
        aborter.join();

        assertNotEquals(0, result.exitCode());
        assertFalse(result.timedOut());
    }

    @Test
    void publishesStdoutAndStderrProgress() {
        HostExecutor executor = new HostExecutor();
        List<ToolProgress> progresses = new ArrayList<>();

        ExecutionResult result = executor.execute(request("printf 'out\\n'; printf 'err\\n' >&2"), progresses::add, () -> false);

        assertEquals(0, result.exitCode());
        assertTrue(progresses.stream().anyMatch(progress ->
            progress.kind() == ToolProgressKind.OUTPUT
                && "stdout".equals(progress.stream())
                && progress.delta().contains("out")));
        assertTrue(progresses.stream().anyMatch(progress ->
            progress.kind() == ToolProgressKind.OUTPUT
                && "stderr".equals(progress.stream())
                && progress.delta().contains("err")));
    }

    @Test
    void waitsForProgressCallbackBeforeReturningAfterNormalExit() {
        HostExecutor executor = new HostExecutor();
        AtomicBoolean stdoutProgressDelivered = new AtomicBoolean(false);

        ExecutionResult result = executor.execute(request("printf slow-progress"), progress -> {
            if (progress.kind() == ToolProgressKind.OUTPUT
                && "stdout".equals(progress.stream())
                && progress.delta().contains("slow-progress")) {
                try {
                    Thread.sleep(800);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                stdoutProgressDelivered.set(true);
            }
        }, () -> false);

        assertEquals(0, result.exitCode());
        assertEquals("slow-progress", result.stdout());
        assertTrue(stdoutProgressDelivered.get());
    }

    @Test
    void capturesAllOutputBeforeReturning() {
        HostExecutor executor = new HostExecutor();

        ExecutionResult result = executor.execute(request("yes x | head -n 20000"), progress -> {
        }, () -> false);

        assertEquals(0, result.exitCode());
        assertEquals(20000, result.stdout().lines().count());
    }

    @Test
    void returnsAfterShellExitEvenWhenBackgroundProcessKeepsPipesOpen() {
        HostExecutor executor = new HostExecutor();
        Instant started = Instant.now();

        ExecutionResult result = executor.execute(request("sleep 2 & printf ok", tempDir, Map.of(), Duration.ofSeconds(5)),
            progress -> {
            }, () -> false);

        assertEquals(0, result.exitCode());
        assertFalse(result.timedOut());
        assertTrue(result.stdout().contains("ok"));
        assertTrue(Duration.between(started, Instant.now()).compareTo(Duration.ofSeconds(2)) < 0);
    }

    private ExecutionRequest request(String command) {
        return request(command, tempDir, Map.of(), Duration.ofSeconds(5));
    }

    private ExecutionRequest request(String command, Path cwd, Map<String, String> env, Duration timeout) {
        return rawRequest(List.of("bash", "-lc", command), cwd, env, timeout);
    }

    private ExecutionRequest rawRequest(List<String> command, Path cwd, Map<String, String> env, Duration timeout) {
        return new ExecutionRequest(
            command,
            cwd,
            env,
            timeout,
            new SandboxRuntimePolicy(List.of(), List.of(), List.of(), List.of(), NetworkMode.DISABLED, false, false)
        );
    }
}
