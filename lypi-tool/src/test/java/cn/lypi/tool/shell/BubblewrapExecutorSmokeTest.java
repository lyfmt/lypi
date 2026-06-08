package cn.lypi.tool.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cn.lypi.contracts.runtime.ExecutionRequest;
import cn.lypi.contracts.runtime.ExecutionResult;
import cn.lypi.contracts.runtime.NetworkMode;
import cn.lypi.contracts.runtime.SandboxRuntimePolicy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BubblewrapExecutorSmokeTest {
    @TempDir
    Path tempDir;

    @Test
    void executesWithRealBubblewrapWhenAvailable() {
        BubblewrapExecutor executor = new BubblewrapExecutor();
        assumeTrue(realBubblewrapWorks(executor), "system bubblewrap is unavailable or cannot create namespaces");

        ExecutionResult result = executor.execute(request("printf real-bwrap"), progress -> {
        }, () -> false);

        assertEquals(0, result.exitCode());
        assertEquals("real-bwrap", result.stdout());
        assertTrue(result.metadata().sandboxed());
        assertEquals("bubblewrap", result.metadata().executorName());
    }

    private boolean realBubblewrapWorks(BubblewrapExecutor executor) {
        ExecutionResult result = executor.execute(request("true"), progress -> {
        }, () -> false);
        return result.exitCode() == 0 && result.metadata().sandboxed();
    }

    private ExecutionRequest request(String command) {
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
                false,
                false
            )
        );
    }
}
