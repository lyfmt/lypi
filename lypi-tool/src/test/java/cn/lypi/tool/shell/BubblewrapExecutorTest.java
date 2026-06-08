package cn.lypi.tool.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
