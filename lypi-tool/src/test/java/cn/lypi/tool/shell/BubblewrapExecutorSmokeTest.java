package cn.lypi.tool.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cn.lypi.contracts.runtime.ExecutionRequest;
import cn.lypi.contracts.runtime.ExecutionResult;
import cn.lypi.contracts.runtime.NetworkMode;
import cn.lypi.contracts.runtime.SandboxRuntimePolicy;
import java.nio.file.Files;
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

    @Test
    void keepsProtectedMetadataReadonlyWithRealBubblewrapWhenAvailable() throws Exception {
        BubblewrapExecutor executor = new BubblewrapExecutor();
        Path probe = Files.createDirectory(tempDir.resolve("probe"));
        assumeTrue(realBubblewrapWorks(executor, probe), "system bubblewrap is unavailable or cannot create namespaces");
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Files.createDirectory(workspace.resolve(".git"));

        ExecutionResult result = executor.execute(request("printf protected > .git/HEAD", workspace), progress -> {
        }, () -> false);

        assertTrue(result.exitCode() != 0);
        assertTrue(result.metadata().sandboxed());
        assertTrue(!Files.exists(workspace.resolve(".git/HEAD")));
    }

    @Test
    void masksDenyReadDirectoryWithRealBubblewrapWhenAvailable() throws Exception {
        BubblewrapExecutor executor = new BubblewrapExecutor();
        Path probe = Files.createDirectory(tempDir.resolve("probe"));
        assumeTrue(realBubblewrapWorks(executor, probe), "system bubblewrap is unavailable or cannot create namespaces");
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path secret = Files.createDirectory(workspace.resolve("secret"));
        Files.writeString(secret.resolve("token"), "hidden");

        ExecutionResult result = executor.execute(request(
            "cat secret/token",
            workspace,
            new SandboxRuntimePolicy(
                List.of(Path.of("/usr"), Path.of("/bin"), Path.of("/lib"), Path.of("/lib64"), Path.of("/etc")),
                List.of(secret),
                List.of(workspace),
                List.of(),
                NetworkMode.DISABLED,
                false,
                false
            )
        ), progress -> {
        }, () -> false);

        assertTrue(result.exitCode() != 0);
        assertTrue(result.metadata().sandboxed());
        assertTrue(!result.stdout().contains("hidden"));
        assertTrue(result.stderr().contains("Permission denied") || result.stderr().contains("No such file or directory"));
        assertEquals("hidden", Files.readString(secret.resolve("token")));
    }

    @Test
    void masksDenyReadFileWithRealBubblewrapWhenAvailable() throws Exception {
        BubblewrapExecutor executor = new BubblewrapExecutor();
        Path probe = Files.createDirectory(tempDir.resolve("probe"));
        assumeTrue(realBubblewrapWorks(executor, probe), "system bubblewrap is unavailable or cannot create namespaces");
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path secret = Files.writeString(workspace.resolve("secret.txt"), "hidden");

        ExecutionResult result = executor.execute(request(
            "cat secret.txt",
            workspace,
            new SandboxRuntimePolicy(
                List.of(Path.of("/usr"), Path.of("/bin"), Path.of("/lib"), Path.of("/lib64"), Path.of("/etc")),
                List.of(secret),
                List.of(workspace),
                List.of(),
                NetworkMode.DISABLED,
                false,
                false
            )
        ), progress -> {
        }, () -> false);

        assertTrue(result.exitCode() != 0);
        assertTrue(result.metadata().sandboxed());
        assertTrue(!result.stdout().contains("hidden"));
        assertTrue(result.stderr().contains("Permission denied") || result.stderr().contains("No such file or directory"));
        assertEquals("hidden", Files.readString(secret));
    }

    private boolean realBubblewrapWorks(BubblewrapExecutor executor) {
        return realBubblewrapWorks(executor, tempDir);
    }

    private boolean realBubblewrapWorks(BubblewrapExecutor executor, Path cwd) {
        ExecutionResult result = executor.execute(request("true", cwd), progress -> {
        }, () -> false);
        return result.exitCode() == 0 && result.metadata().sandboxed();
    }

    private ExecutionRequest request(String command) {
        return request(command, tempDir);
    }

    private ExecutionRequest request(String command, Path cwd) {
        return request(
            command,
            cwd,
            new SandboxRuntimePolicy(
                List.of(Path.of("/usr"), Path.of("/bin"), Path.of("/lib"), Path.of("/lib64"), Path.of("/etc")),
                List.of(),
                List.of(cwd),
                List.of(),
                NetworkMode.DISABLED,
                false,
                false
            )
        );
    }

    private ExecutionRequest request(String command, Path cwd, SandboxRuntimePolicy policy) {
        return new ExecutionRequest(
            List.of("bash", "-lc", command),
            cwd,
            Map.of(),
            Duration.ofSeconds(5),
            policy
        );
    }
}
