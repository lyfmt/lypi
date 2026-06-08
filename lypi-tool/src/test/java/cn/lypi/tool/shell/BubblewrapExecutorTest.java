package cn.lypi.tool.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.common.ToolProgressKind;
import cn.lypi.contracts.runtime.ExecutionRequest;
import cn.lypi.contracts.runtime.ExecutionResult;
import cn.lypi.contracts.runtime.NetworkMode;
import cn.lypi.contracts.runtime.SandboxRuntimePolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BubblewrapExecutorTest {
    @TempDir
    Path tempDir;

    @Test
    void executesCommandThroughAvailableBwrap() throws Exception {
        Path fakeBwrap = fakeBwrap();
        BubblewrapExecutor executor = new BubblewrapExecutor(
            BubblewrapCommandBuilder.defaults(),
            new HostExecutor(),
            () -> Optional.of(fakeBwrap)
        );

        ExecutionResult result = executor.execute(request("printf sandbox", false), progress -> {
        }, () -> false);

        assertEquals(0, result.exitCode());
        assertEquals("sandbox", result.stdout());
        assertTrue(result.metadata().sandboxed());
        assertEquals("bubblewrap", result.metadata().executorName());
        assertTrue(result.metadata().diagnostic().isEmpty());
    }

    @Test
    void keepsSandboxMetadataWhenSandboxedCommandWritesBwrapLikeStderr() throws Exception {
        Path fakeBwrap = fakeBwrap();
        BubblewrapExecutor executor = new BubblewrapExecutor(
            BubblewrapCommandBuilder.defaults(),
            new HostExecutor(),
            () -> Optional.of(fakeBwrap)
        );

        ExecutionResult result = executor.execute(request("echo 'bwrap: user command failed' >&2; exit 1", false), progress -> {
        }, () -> false);

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("bwrap: user command failed"));
        assertTrue(result.metadata().sandboxed());
        assertEquals("bubblewrap", result.metadata().executorName());
    }

    @Test
    void keepsSandboxStartedSentinelOutOfProgress() throws Exception {
        Path fakeBwrap = fakeBwrap();
        BubblewrapExecutor executor = new BubblewrapExecutor(
            BubblewrapCommandBuilder.defaults(),
            new HostExecutor(),
            () -> Optional.of(fakeBwrap)
        );
        List<ToolProgress> progresses = new java.util.ArrayList<>();

        ExecutionResult result = executor.execute(request("printf user-progress >&2", false), progresses::add, () -> false);

        assertEquals(0, result.exitCode());
        assertEquals("user-progress", result.stderr());
        assertTrue(progresses.stream()
            .filter(progress -> progress.kind() == ToolProgressKind.OUTPUT)
            .noneMatch(progress -> progress.delta() != null && progress.delta().contains("__LYPI_BWRAP_STARTED__")));
    }

    @Test
    void fallsBackToHostExecutorWhenBwrapUnavailableByDefault() {
        BubblewrapExecutor executor = new BubblewrapExecutor(
            BubblewrapCommandBuilder.defaults(),
            new HostExecutor(),
            Optional::empty
        );

        ExecutionResult result = executor.execute(request("printf host", false), progress -> {
        }, () -> false);

        assertEquals(0, result.exitCode());
        assertEquals("host", result.stdout());
        assertFalse(result.metadata().sandboxed());
        assertEquals("host", result.metadata().executorName());
        assertTrue(result.metadata().diagnostic().orElseThrow().contains("bubblewrap unavailable"));
    }

    @Test
    void doesNotFallBackToHostWhenBwrapUnavailableAndPolicyContainsDenyRead() throws Exception {
        BubblewrapExecutor executor = new BubblewrapExecutor(
            BubblewrapCommandBuilder.defaults(),
            new HostExecutor(),
            Optional::empty
        );

        ExecutionResult result = executor.execute(requestWithDenyRead("printf should-not-run"), progress -> {
        }, () -> false);

        assertNotEquals(0, result.exitCode());
        assertEquals("", result.stdout());
        assertTrue(result.stderr().contains("bubblewrap unavailable"));
        assertFalse(result.metadata().sandboxed());
        assertEquals("bubblewrap", result.metadata().executorName());
    }

    @Test
    void failsWhenBwrapUnavailableAndPolicyRequiresSandbox() {
        BubblewrapExecutor executor = new BubblewrapExecutor(
            BubblewrapCommandBuilder.defaults(),
            new HostExecutor(),
            Optional::empty
        );

        ExecutionResult result = executor.execute(request("printf ignored", true), progress -> {
        }, () -> false);

        assertNotEquals(0, result.exitCode());
        assertTrue(result.stderr().contains("bubblewrap unavailable"));
        assertFalse(result.metadata().sandboxed());
        assertEquals("bubblewrap", result.metadata().executorName());
    }

    @Test
    void fallsBackToHostExecutorWhenBwrapPreflightFailsByDefault() throws Exception {
        Path failingBwrap = failingBwrap();
        BubblewrapExecutor executor = new BubblewrapExecutor(
            BubblewrapCommandBuilder.defaults(),
            new HostExecutor(),
            () -> Optional.of(failingBwrap)
        );

        ExecutionResult result = executor.execute(request("printf host-after-preflight", false), progress -> {
        }, () -> false);

        assertEquals(0, result.exitCode());
        assertEquals("host-after-preflight", result.stdout());
        assertFalse(result.metadata().sandboxed());
        assertEquals("host", result.metadata().executorName());
        assertTrue(result.metadata().diagnostic().orElseThrow().contains("bubblewrap unavailable"));
    }

    @Test
    void retriesWithoutProcMountWhenProcPreflightFails() throws Exception {
        Path procFailingBwrap = procFailingBwrap();
        BubblewrapExecutor executor = new BubblewrapExecutor(
            BubblewrapCommandBuilder.defaults(),
            new HostExecutor(),
            () -> Optional.of(procFailingBwrap)
        );

        ExecutionResult result = executor.execute(request("printf sandbox-after-proc-fallback", false), progress -> {
        }, () -> false);

        assertEquals(0, result.exitCode());
        assertEquals("sandbox-after-proc-fallback", result.stdout());
        assertTrue(result.metadata().sandboxed());
        assertEquals("bubblewrap", result.metadata().executorName());
    }

    @Test
    void doesNotRetryWithoutProcMountForUnrelatedPreflightPermissionFailure() throws Exception {
        Path unrelatedFailingBwrap = unrelatedPreflightFailingBwrap();
        BubblewrapExecutor executor = new BubblewrapExecutor(
            BubblewrapCommandBuilder.defaults(),
            new HostExecutor(),
            () -> Optional.of(unrelatedFailingBwrap)
        );

        ExecutionResult result = executor.execute(request("printf host-after-unrelated-failure", false), progress -> {
        }, () -> false);

        assertEquals(0, result.exitCode());
        assertEquals("host-after-unrelated-failure", result.stdout());
        assertFalse(result.metadata().sandboxed());
        assertEquals("host", result.metadata().executorName());
        assertTrue(result.metadata().diagnostic().orElseThrow().contains("bubblewrap unavailable"));
    }

    @Test
    void failsWhenProcFallbackPreflightFailsAndPolicyRequiresSandbox() throws Exception {
        Path procAndNoProcFailingBwrap = procAndNoProcFailingBwrap();
        BubblewrapExecutor executor = new BubblewrapExecutor(
            BubblewrapCommandBuilder.defaults(),
            new HostExecutor(),
            () -> Optional.of(procAndNoProcFailingBwrap)
        );

        ExecutionResult result = executor.execute(request("printf ignored", true), progress -> {
        }, () -> false);

        assertNotEquals(0, result.exitCode());
        assertTrue(result.stderr().contains("bubblewrap unavailable"));
        assertTrue(result.stderr().contains("preflight failed exit"));
        assertFalse(result.metadata().sandboxed());
        assertEquals("bubblewrap", result.metadata().executorName());
    }

    @Test
    void fallsBackToHostExecutorWhenBwrapSetupFailsAfterPreflightByDefault() throws Exception {
        Path setupFailingBwrap = setupFailingBwrap();
        BubblewrapExecutor executor = new BubblewrapExecutor(
            BubblewrapCommandBuilder.defaults(),
            new HostExecutor(),
            () -> Optional.of(setupFailingBwrap)
        );

        ExecutionResult result = executor.execute(request("printf host-after-setup-failure", false), progress -> {
        }, () -> false);

        assertEquals(0, result.exitCode());
        assertEquals("host-after-setup-failure", result.stdout());
        assertFalse(result.metadata().sandboxed());
        assertEquals("host", result.metadata().executorName());
        assertTrue(result.metadata().diagnostic().orElseThrow().contains("bubblewrap execution failed"));
    }

    @Test
    void doesNotFallBackToHostWhenBwrapSetupFailsAndPolicyContainsDenyRead() throws Exception {
        Path setupFailingBwrap = setupFailingBwrap();
        BubblewrapExecutor executor = new BubblewrapExecutor(
            BubblewrapCommandBuilder.defaults(),
            new HostExecutor(),
            () -> Optional.of(setupFailingBwrap)
        );

        ExecutionResult result = executor.execute(requestWithDenyRead("printf should-not-run"), progress -> {
        }, () -> false);

        assertNotEquals(0, result.exitCode());
        assertTrue(!result.stdout().contains("should-not-run"));
        assertTrue(result.stderr().contains("bwrap: setup failed"));
        assertFalse(result.metadata().sandboxed());
        assertEquals("bubblewrap", result.metadata().executorName());
        assertTrue(result.metadata().diagnostic().orElseThrow().contains("bubblewrap execution failed"));
    }

    @Test
    void failsWhenBwrapSetupFailsAfterPreflightAndPolicyRequiresSandbox() throws Exception {
        Path setupFailingBwrap = setupFailingBwrap();
        BubblewrapExecutor executor = new BubblewrapExecutor(
            BubblewrapCommandBuilder.defaults(),
            new HostExecutor(),
            () -> Optional.of(setupFailingBwrap)
        );

        ExecutionResult result = executor.execute(request("printf ignored", true), progress -> {
        }, () -> false);

        assertNotEquals(0, result.exitCode());
        assertTrue(result.stderr().contains("bwrap: setup failed"));
        assertFalse(result.metadata().sandboxed());
        assertEquals("bubblewrap", result.metadata().executorName());
        assertTrue(result.metadata().diagnostic().orElseThrow().contains("bubblewrap execution failed"));
    }

    @Test
    void failsWhenBwrapPreflightFailsAndPolicyRequiresSandbox() throws Exception {
        Path failingBwrap = failingBwrap();
        BubblewrapExecutor executor = new BubblewrapExecutor(
            BubblewrapCommandBuilder.defaults(),
            new HostExecutor(),
            () -> Optional.of(failingBwrap)
        );

        ExecutionResult result = executor.execute(request("printf ignored", true), progress -> {
        }, () -> false);

        assertNotEquals(0, result.exitCode());
        assertTrue(result.stderr().contains("bubblewrap unavailable"));
        assertFalse(result.metadata().sandboxed());
        assertEquals("bubblewrap", result.metadata().executorName());
    }

    private ExecutionRequest request(String command, boolean failIfUnavailable) {
        return new ExecutionRequest(
            List.of("bash", "-lc", command),
            tempDir,
            Map.of(),
            Duration.ofSeconds(5),
            new SandboxRuntimePolicy(
                List.of(Path.of("/usr"), Path.of("/bin"), Path.of("/lib"), Path.of("/lib64"), Path.of("/etc")),
                List.of(),
                List.of(tempDir),
                List.of(),
                NetworkMode.DISABLED,
                failIfUnavailable,
                false
            )
        );
    }

    private ExecutionRequest requestWithDenyRead(String command) throws Exception {
        Path secret = Files.createDirectories(tempDir.resolve("secret"));
        return new ExecutionRequest(
            List.of("bash", "-lc", command),
            tempDir,
            Map.of(),
            Duration.ofSeconds(5),
            new SandboxRuntimePolicy(
                List.of(Path.of("/usr"), Path.of("/bin"), Path.of("/lib"), Path.of("/lib64"), Path.of("/etc")),
                List.of(secret),
                List.of(tempDir),
                List.of(),
                NetworkMode.DISABLED,
                false,
                false
            )
        );
    }

    private Path fakeBwrap() throws Exception {
        Path script = tempDir.resolve("fake-bwrap.sh");
        Files.writeString(script, """
            #!/bin/sh
            while [ "$#" -gt 0 ] && [ "$1" != "--" ]; do
              shift
            done
            if [ "$1" = "--" ]; then
              shift
            fi
            exec "$@"
            """);
        script.toFile().setExecutable(true);
        return script;
    }

    private Path procFailingBwrap() throws Exception {
        Path script = tempDir.resolve("proc-failing-bwrap.sh");
        Files.writeString(script, """
            #!/bin/sh
            for arg in "$@"; do
              if [ "$arg" = "--proc" ]; then
                echo "bwrap: Can't mount proc on /proc: Operation not permitted" >&2
                exit 1
              fi
            done
            while [ "$#" -gt 0 ] && [ "$1" != "--" ]; do
              shift
            done
            if [ "$1" = "--" ]; then
              shift
            fi
            exec "$@"
            """);
        script.toFile().setExecutable(true);
        return script;
    }

    private Path unrelatedPreflightFailingBwrap() throws Exception {
        Path script = tempDir.resolve("unrelated-preflight-failing-bwrap.sh");
        Files.writeString(script, """
            #!/bin/sh
            echo "bwrap: Can't bind mount /dev/null: Operation not permitted" >&2
            exit 1
            """);
        script.toFile().setExecutable(true);
        return script;
    }

    private Path procAndNoProcFailingBwrap() throws Exception {
        Path script = tempDir.resolve("proc-and-no-proc-failing-bwrap.sh");
        Files.writeString(script, """
            #!/bin/sh
            for arg in "$@"; do
              if [ "$arg" = "--proc" ]; then
                echo "bwrap: Can't mount proc on /proc: Operation not permitted" >&2
                exit 1
              fi
            done
            echo "bwrap: still unavailable without proc" >&2
            exit 42
            """);
        script.toFile().setExecutable(true);
        return script;
    }

    private Path setupFailingBwrap() throws Exception {
        Path script = tempDir.resolve("setup-failing-bwrap.sh");
        Files.writeString(script, """
            #!/bin/sh
            if [ "${1:-}" = "--new-session" ]; then
              while [ "$#" -gt 0 ] && [ "$1" != "--" ]; do
                shift
              done
              if [ "$1" = "--" ]; then
                shift
              fi
              if [ "${1:-}" = "/usr/bin/true" ] || [ "${1:-}" = "/bin/true" ] || [ "${1:-}" = "true" ]; then
                exec "$@"
              fi
            fi
            echo 'bwrap: setup failed' >&2
            exit 1
            """);
        script.toFile().setExecutable(true);
        return script;
    }

    private Path failingBwrap() throws Exception {
        Path script = tempDir.resolve("failing-bwrap.sh");
        Files.writeString(script, """
            #!/bin/sh
            echo namespace failed >&2
            exit 42
            """);
        script.toFile().setExecutable(true);
        return script;
    }
}
