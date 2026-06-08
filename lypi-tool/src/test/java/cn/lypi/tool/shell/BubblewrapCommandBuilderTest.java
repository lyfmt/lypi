package cn.lypi.tool.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.runtime.ExecutionRequest;
import cn.lypi.contracts.runtime.NetworkMode;
import cn.lypi.contracts.runtime.SandboxRuntimePolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BubblewrapCommandBuilderTest {
    @TempDir
    Path tempDir;

    @Test
    void buildsMinimalNetworkDisabledBwrapArgv() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        ExecutionRequest request = request(cwd, policy(workspace, NetworkMode.DISABLED));

        List<String> argv = BubblewrapCommandBuilder.defaults().build(request);

        assertEquals("bwrap", argv.getFirst());
        assertTrue(argv.contains("--new-session"));
        assertTrue(argv.contains("--die-with-parent"));
        assertTrue(argv.contains("--unshare-user"));
        assertTrue(argv.contains("--unshare-pid"));
        assertTrue(argv.contains("--unshare-net"));
        assertContainsSequence(argv, "--ro-bind-try", "/usr", "/usr");
        assertContainsSequence(argv, "--ro-bind-try", "/bin", "/bin");
        assertContainsSequence(argv, "--ro-bind-try", "/lib", "/lib");
        assertContainsSequence(argv, "--ro-bind-try", "/lib64", "/lib64");
        assertContainsSequence(argv, "--ro-bind-try", "/etc", "/etc");
        assertContainsSequence(argv, "--dev", "/dev");
        assertContainsSequence(argv, "--proc", "/proc");
        assertTrue(indexOfSequence(argv, "--tmpfs", "/tmp") < indexOfSequence(argv, "--bind", workspace.toString(), workspace.toString()));
        assertContainsSequence(argv, "--bind", workspace.toString(), workspace.toString());
        assertContainsSequence(argv, "--tmpfs", "/tmp");
        assertContainsSequence(argv, "--chdir", cwd.toString());
        assertCommandSuffix(argv, List.of("bash", "-lc", "printf hello"));
    }

    @Test
    void omitsNetworkUnshareForHostNetworkMode() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        ExecutionRequest request = request(cwd, policy(workspace, NetworkMode.HOST));

        List<String> argv = BubblewrapCommandBuilder.defaults().build(request);

        assertTrue(!argv.contains("--unshare-net"));
    }

    @Test
    void canOmitProcMountWhenRequested() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        ExecutionRequest request = request(cwd, policy(workspace, NetworkMode.DISABLED));

        List<String> argv = BubblewrapCommandBuilder.defaults()
            .build(request, new BubblewrapCommandBuilder.Options(false));

        assertTrue(!containsSequence(argv, "--proc", "/proc"));
        assertContainsSequence(argv, "--dev", "/dev");
        assertContainsSequence(argv, "--bind", workspace.toString(), workspace.toString());
        assertCommandSuffix(argv, List.of("bash", "-lc", "printf hello"));
    }

    @Test
    void rebindsExistingProtectedMetadataUnderWritableWorkspaceAsReadOnly() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path git = Files.createDirectory(workspace.resolve(".git"));
        Path codex = Files.createDirectory(workspace.resolve(".codex"));
        Path agents = Files.createDirectory(workspace.resolve(".agents"));
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        ExecutionRequest request = request(cwd, policy(workspace, NetworkMode.DISABLED));

        List<String> argv = BubblewrapCommandBuilder.defaults().build(request);

        int workspaceBind = indexOfSequence(argv, "--bind", workspace.toString(), workspace.toString());
        assertTrue(workspaceBind >= 0, "workspace must be writable");
        assertTrue(indexOfSequence(argv, "--ro-bind", git.toString(), git.toString()) > workspaceBind);
        assertTrue(indexOfSequence(argv, "--ro-bind", codex.toString(), codex.toString()) > workspaceBind);
        assertTrue(indexOfSequence(argv, "--ro-bind", agents.toString(), agents.toString()) > workspaceBind);
    }

    @Test
    void skipsMissingProtectedMetadataPaths() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        ExecutionRequest request = request(cwd, policy(workspace, NetworkMode.DISABLED));

        List<String> argv = BubblewrapCommandBuilder.defaults().build(request);

        assertTrue(!containsSequence(argv, "--ro-bind", workspace.resolve(".git").toString(), workspace.resolve(".git").toString()));
        assertTrue(!containsSequence(argv, "--ro-bind", workspace.resolve(".codex").toString(), workspace.resolve(".codex").toString()));
        assertTrue(!containsSequence(argv, "--ro-bind", workspace.resolve(".agents").toString(), workspace.resolve(".agents").toString()));
    }

    @Test
    void rebindsExistingProtectedMetadataFileAsReadOnly() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path git = workspace.resolve(".git");
        Files.writeString(git, "gitdir: ../.git/worktrees/example\n");
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        ExecutionRequest request = request(cwd, policy(workspace, NetworkMode.DISABLED));

        List<String> argv = BubblewrapCommandBuilder.defaults().build(request);

        int workspaceBind = indexOfSequence(argv, "--bind", workspace.toString(), workspace.toString());
        assertTrue(indexOfSequence(argv, "--ro-bind", git.toString(), git.toString()) > workspaceBind);
    }

    @Test
    void rejectsProtectedMetadataSymlinkInsteadOfBuildingUnsafeBind() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Files.createDirectory(workspace.resolve("realgit"));
        Path git = workspace.resolve(".git");
        Files.createSymbolicLink(git, Path.of("realgit"));
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        ExecutionRequest request = request(cwd, policy(workspace, NetworkMode.DISABLED));

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> BubblewrapCommandBuilder.defaults().build(request)
        );

        assertTrue(exception.getMessage().contains("protected metadata"));
        assertTrue(exception.getMessage().contains("symbolic link"));
    }

    @Test
    void protectedMetadataReadonlyBindWinsOverNestedWritableRoot() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path git = Files.createDirectory(workspace.resolve(".git"));
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/usr"), Path.of("/bin"), Path.of("/lib"), Path.of("/lib64"), Path.of("/etc")),
            List.of(),
            List.of(workspace, git),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );

        List<String> argv = BubblewrapCommandBuilder.defaults().build(request(cwd, policy));

        int nestedWritableBind = indexOfSequence(argv, "--bind", git.toString(), git.toString());
        assertTrue(nestedWritableBind >= 0, "test setup must include nested writable bind");
        assertTrue(indexOfSequence(argv, "--ro-bind", git.toString(), git.toString()) > nestedWritableBind);
    }

    @Test
    void masksExistingDenyReadDirectoryAfterWritableWorkspaceBind() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path secret = Files.createDirectory(workspace.resolve("secret"));
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/usr"), Path.of("/bin"), Path.of("/lib"), Path.of("/lib64"), Path.of("/etc")),
            List.of(secret),
            List.of(workspace),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );

        List<String> argv = BubblewrapCommandBuilder.defaults().build(request(cwd, policy));

        int workspaceBind = indexOfSequence(argv, "--bind", workspace.toString(), workspace.toString());
        assertTrue(workspaceBind >= 0, "workspace must be writable");
        int denyReadMask = indexOfSequence(argv, "--perms", "000", "--tmpfs", secret.toString(), "--remount-ro", secret.toString());
        assertTrue(denyReadMask > workspaceBind);
        assertTrue(denyReadMask < argv.lastIndexOf("--"));
    }

    @Test
    void rejectsAllowWriteSymlinkInsteadOfLeavingAliasBypass() throws Exception {
        Path realWorkspace = Files.createDirectory(tempDir.resolve("real-workspace"));
        Path workspaceLink = tempDir.resolve("workspace-link");
        Files.createSymbolicLink(workspaceLink, realWorkspace);
        Path cwd = Files.createDirectory(realWorkspace.resolve("src"));
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/usr")),
            List.of(),
            List.of(workspaceLink),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> BubblewrapCommandBuilder.defaults().build(request(cwd, policy))
        );

        assertTrue(exception.getMessage().contains("allowWrite path must not cross a symbolic link"));
    }

    @Test
    void rejectsCwdInsideDenyReadDirectoryToAvoidSandboxStartupFallback() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path secret = Files.createDirectory(workspace.resolve("secret"));
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/usr")),
            List.of(secret),
            List.of(workspace),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> BubblewrapCommandBuilder.defaults().build(request(secret, policy))
        );

        assertTrue(exception.getMessage().contains("cwd inside denyRead"));
        assertTrue(exception.getMessage().contains("unsupported"));
    }

    @Test
    void rejectsNestedDenyReadDirectoriesUntilOrderingIsSupported() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path secret = Files.createDirectory(workspace.resolve("secret"));
        Path nested = Files.createDirectory(secret.resolve("nested"));
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/usr")),
            List.of(secret, nested),
            List.of(workspace),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> BubblewrapCommandBuilder.defaults().build(request(cwd, policy))
        );

        assertTrue(exception.getMessage().contains("nested denyRead"));
        assertTrue(exception.getMessage().contains("unsupported"));
    }

    @Test
    void rejectsUnsupportedDenyWriteInsteadOfIgnoringIt() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/usr")),
            List.of(),
            List.of(workspace),
            List.of(workspace.resolve("secret")),
            NetworkMode.DISABLED,
            false,
            false
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> BubblewrapCommandBuilder.defaults().build(request(cwd, policy))
        );

        assertTrue(exception.getMessage().contains("denyWrite"));
        assertTrue(exception.getMessage().contains("unsupported"));
    }

    @Test
    void masksExistingDenyReadFileAfterWritableWorkspaceBind() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path secretFile = Files.writeString(workspace.resolve("secret.txt"), "secret");
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/usr")),
            List.of(secretFile),
            List.of(workspace),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );

        List<String> argv = BubblewrapCommandBuilder.defaults().build(request(cwd, policy));

        int workspaceBind = indexOfSequence(argv, "--bind", workspace.toString(), workspace.toString());
        assertTrue(workspaceBind >= 0, "workspace must be writable");
        int denyReadMask = indexOfSequence(argv, "--perms", "000", "--ro-bind-data", "0", secretFile.toString());
        assertTrue(denyReadMask > workspaceBind);
        assertTrue(denyReadMask < argv.lastIndexOf("--"));
    }

    @Test
    void rejectsDenyReadSymlinkInsteadOfBuildingUnsafeMask() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Files.createDirectory(workspace.resolve("real-secret"));
        Path secretLink = workspace.resolve("secret");
        Files.createSymbolicLink(secretLink, Path.of("real-secret"));
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/usr")),
            List.of(secretLink),
            List.of(workspace),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> BubblewrapCommandBuilder.defaults().build(request(cwd, policy))
        );

        assertTrue(exception.getMessage().contains("denyRead path must not cross a symbolic link"));
    }

    @Test
    void rejectsMissingDenyReadPathUntilSyntheticMaskingIsSupported() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path missingSecret = workspace.resolve("missing-secret");
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/usr")),
            List.of(missingSecret),
            List.of(workspace),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> BubblewrapCommandBuilder.defaults().build(request(cwd, policy))
        );

        assertTrue(exception.getMessage().contains("missing denyRead path"));
        assertTrue(exception.getMessage().contains("unsupported"));
    }

    @Test
    void rejectsDenyReadAncestorOfWritableRootUntilReopenIsSupported() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path denied = Files.createDirectory(workspace.resolve("denied"));
        Path writableChild = Files.createDirectory(denied.resolve("child"));
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/usr")),
            List.of(denied),
            List.of(workspace, writableChild),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> BubblewrapCommandBuilder.defaults().build(request(cwd, policy))
        );

        assertTrue(exception.getMessage().contains("denyRead ancestor of allowWrite"));
        assertTrue(exception.getMessage().contains("unsupported"));
    }

    @Test
    void rejectsRelativeMountPaths() {
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("relative-read")),
            List.of(),
            List.of(Path.of("relative-write")),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> BubblewrapCommandBuilder.defaults().build(request(Path.of("relative-cwd"), policy))
        );

        assertTrue(exception.getMessage().contains("absolute"));
    }

    private ExecutionRequest request(Path cwd, SandboxRuntimePolicy policy) {
        return new ExecutionRequest(
            List.of("bash", "-lc", "printf hello"),
            cwd,
            Map.of(),
            Duration.ofSeconds(5),
            policy
        );
    }

    private SandboxRuntimePolicy policy(Path workspace, NetworkMode networkMode) {
        return new SandboxRuntimePolicy(
            List.of(Path.of("/usr"), Path.of("/bin"), Path.of("/lib"), Path.of("/lib64"), Path.of("/etc")),
            List.of(),
            List.of(workspace),
            List.of(),
            networkMode,
            false,
            false
        );
    }

    private void assertContainsSequence(List<String> argv, String... sequence) {
        if (containsSequence(argv, sequence)) {
            return;
        }
        throw new AssertionError("missing sequence " + List.of(sequence) + " in " + argv);
    }

    private boolean containsSequence(List<String> argv, String... sequence) {
        return indexOfSequence(argv, sequence) >= 0;
    }

    private int indexOfSequence(List<String> argv, String... sequence) {
        for (int index = 0; index <= argv.size() - sequence.length; index++) {
            boolean matches = true;
            for (int offset = 0; offset < sequence.length; offset++) {
                matches = matches && sequence[offset].equals(argv.get(index + offset));
            }
            if (matches) {
                return index;
            }
        }
        return -1;
    }

    private void assertCommandSuffix(List<String> argv, List<String> command) {
        int separator = argv.lastIndexOf("--");
        assertTrue(separator >= 0, "bwrap argv must contain -- separator");
        assertEquals(command, argv.subList(separator + 1, argv.size()));
    }
}
