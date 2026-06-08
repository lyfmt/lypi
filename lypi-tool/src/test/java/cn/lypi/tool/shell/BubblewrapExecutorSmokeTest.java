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
    void masksMissingProtectedMetadataWithRealBubblewrapWhenAvailable() throws Exception {
        BubblewrapExecutor executor = new BubblewrapExecutor();
        Path probe = Files.createDirectory(tempDir.resolve("probe"));
        assumeTrue(realBubblewrapWorks(executor, probe), "system bubblewrap is unavailable or cannot create namespaces");
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));

        ExecutionResult result = executor.execute(request("printf bad > .git/HEAD || printf protected; test -d .git", workspace), progress -> {
        }, () -> false);

        assertEquals(0, result.exitCode());
        assertTrue(result.metadata().sandboxed());
        assertEquals("protected", result.stdout());
        assertTrue(!Files.exists(workspace.resolve(".git")));
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
    void masksMissingDenyReadPathWithRealBubblewrapWhenAvailable() throws Exception {
        BubblewrapExecutor executor = new BubblewrapExecutor();
        Path probe = Files.createDirectory(tempDir.resolve("probe"));
        assumeTrue(realBubblewrapWorks(executor, probe), "system bubblewrap is unavailable or cannot create namespaces");
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path missingSecret = workspace.resolve("missing-secret").resolve("token");

        ExecutionResult result = executor.execute(request(
            "mkdir -p missing-secret 2>/dev/null || true; printf hidden > missing-secret/token || printf protected",
            workspace,
            new SandboxRuntimePolicy(
                List.of(Path.of("/usr"), Path.of("/bin"), Path.of("/lib"), Path.of("/lib64"), Path.of("/etc")),
                List.of(missingSecret),
                List.of(workspace),
                List.of(),
                NetworkMode.DISABLED,
                false,
                false
            )
        ), progress -> {
        }, () -> false);

        assertEquals(0, result.exitCode());
        assertTrue(result.metadata().sandboxed());
        assertEquals("protected", result.stdout());
        assertTrue(!Files.exists(workspace.resolve("missing-secret")));
    }

    @Test
    void reopensWritableChildUnderDenyReadDirectoryWithRealBubblewrapWhenAvailable() throws Exception {
        BubblewrapExecutor executor = new BubblewrapExecutor();
        Path probe = Files.createDirectory(tempDir.resolve("probe"));
        assumeTrue(realBubblewrapWorks(executor, probe), "system bubblewrap is unavailable or cannot create namespaces");
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path denied = Files.createDirectory(workspace.resolve("denied"));
        Files.writeString(denied.resolve("secret"), "hidden");
        Path allowed = Files.createDirectory(denied.resolve("allowed"));

        ExecutionResult result = executor.execute(request(
            "if cat denied/secret; then printf leaked; else printf blocked; fi; printf '\\n'; printf ok > denied/allowed/out; cat denied/allowed/out",
            workspace,
            new SandboxRuntimePolicy(
                List.of(Path.of("/usr"), Path.of("/bin"), Path.of("/lib"), Path.of("/lib64"), Path.of("/etc")),
                List.of(denied),
                List.of(workspace, allowed),
                List.of(),
                NetworkMode.DISABLED,
                false,
                false
            )
        ), progress -> {
        }, () -> false);

        assertEquals(0, result.exitCode());
        assertTrue(result.metadata().sandboxed());
        assertEquals("blocked\nok", result.stdout());
        assertTrue(!result.stdout().contains("hidden"));
        assertEquals("ok", Files.readString(allowed.resolve("out")));
    }

    @Test
    void reopensWritableFileUnderDenyReadDirectoryWithRealBubblewrapWhenAvailable() throws Exception {
        BubblewrapExecutor executor = new BubblewrapExecutor();
        Path probe = Files.createDirectory(tempDir.resolve("probe"));
        assumeTrue(realBubblewrapWorks(executor, probe), "system bubblewrap is unavailable or cannot create namespaces");
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path denied = Files.createDirectory(workspace.resolve("denied"));
        Files.writeString(denied.resolve("secret"), "hidden");
        Path allowed = Files.createDirectory(denied.resolve("allowed"));
        Path note = Files.writeString(allowed.resolve("note.txt"), "original");

        ExecutionResult result = executor.execute(request(
            "if cat denied/secret; then printf leaked; else printf blocked; fi; printf '\\n'; printf updated > denied/allowed/note.txt; test ! -d denied/allowed/note.txt; cat denied/allowed/note.txt",
            workspace,
            new SandboxRuntimePolicy(
                List.of(Path.of("/usr"), Path.of("/bin"), Path.of("/lib"), Path.of("/lib64"), Path.of("/etc")),
                List.of(denied),
                List.of(workspace, note),
                List.of(),
                NetworkMode.DISABLED,
                false,
                false
            )
        ), progress -> {
        }, () -> false);

        assertEquals(0, result.exitCode());
        assertTrue(result.metadata().sandboxed());
        assertEquals("blocked\nupdated", result.stdout());
        assertTrue(!result.stdout().contains("hidden"));
        assertEquals("updated", Files.readString(note));
    }

    @Test
    void chdirsIntoReopenedWritableChildWithRealBubblewrapWhenAvailable() throws Exception {
        BubblewrapExecutor executor = new BubblewrapExecutor();
        Path probe = Files.createDirectory(tempDir.resolve("probe"));
        assumeTrue(realBubblewrapWorks(executor, probe), "system bubblewrap is unavailable or cannot create namespaces");
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path denied = Files.createDirectory(workspace.resolve("denied"));
        Path allowed = Files.createDirectory(denied.resolve("allowed"));

        ExecutionResult result = executor.execute(request(
            "pwd; printf ok > out",
            allowed,
            new SandboxRuntimePolicy(
                List.of(Path.of("/usr"), Path.of("/bin"), Path.of("/lib"), Path.of("/lib64"), Path.of("/etc")),
                List.of(denied),
                List.of(workspace, allowed),
                List.of(),
                NetworkMode.DISABLED,
                false,
                false
            )
        ), progress -> {
        }, () -> false);

        assertEquals(0, result.exitCode());
        assertTrue(result.metadata().sandboxed());
        assertEquals(allowed.toString(), result.stdout().trim());
        assertEquals("ok", Files.readString(allowed.resolve("out")));
    }

    @Test
    void keepsProtectedMetadataReadonlyAfterReopeningWritableChildWithRealBubblewrapWhenAvailable() throws Exception {
        BubblewrapExecutor executor = new BubblewrapExecutor();
        Path probe = Files.createDirectory(tempDir.resolve("probe"));
        assumeTrue(realBubblewrapWorks(executor, probe), "system bubblewrap is unavailable or cannot create namespaces");
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path denied = Files.createDirectory(workspace.resolve("denied"));
        Path allowed = Files.createDirectory(denied.resolve("allowed"));
        Path git = Files.createDirectory(allowed.resolve(".git"));

        ExecutionResult result = executor.execute(request(
            "printf bad > denied/allowed/.git/HEAD || printf protected; test ! -e denied/allowed/.git/HEAD",
            workspace,
            new SandboxRuntimePolicy(
                List.of(Path.of("/usr"), Path.of("/bin"), Path.of("/lib"), Path.of("/lib64"), Path.of("/etc")),
                List.of(denied),
                List.of(workspace, allowed),
                List.of(),
                NetworkMode.DISABLED,
                false,
                false
            )
        ), progress -> {
        }, () -> false);

        assertEquals(0, result.exitCode());
        assertTrue(result.metadata().sandboxed());
        assertEquals("protected", result.stdout());
        assertTrue(!Files.exists(git.resolve("HEAD")));
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

    @Test
    void masksMultipleDenyReadFilesWithRealBubblewrapWhenAvailable() throws Exception {
        BubblewrapExecutor executor = new BubblewrapExecutor();
        Path probe = Files.createDirectory(tempDir.resolve("probe"));
        assumeTrue(realBubblewrapWorks(executor, probe), "system bubblewrap is unavailable or cannot create namespaces");
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path firstSecret = Files.writeString(workspace.resolve("first-secret.txt"), "hidden-one");
        Path secondSecret = Files.writeString(workspace.resolve("second-secret.txt"), "hidden-two");

        ExecutionResult result = executor.execute(request(
            "cat first-secret.txt second-secret.txt",
            workspace,
            new SandboxRuntimePolicy(
                List.of(Path.of("/usr"), Path.of("/bin"), Path.of("/lib"), Path.of("/lib64"), Path.of("/etc")),
                List.of(firstSecret, secondSecret),
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
        assertTrue(!result.stdout().contains("hidden-one"));
        assertTrue(!result.stdout().contains("hidden-two"));
        assertTrue(result.stderr().contains("Permission denied") || result.stderr().contains("No such file or directory"));
        assertEquals("hidden-one", Files.readString(firstSecret));
        assertEquals("hidden-two", Files.readString(secondSecret));
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
