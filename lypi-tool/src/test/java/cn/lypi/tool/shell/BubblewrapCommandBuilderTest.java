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
        int emptyRoot = indexOfSequence(argv, "--tmpfs", "/");
        assertTrue(emptyRoot >= 0, "restricted sandbox must start from an empty root");
        assertTrue(emptyRoot < indexOfSequence(argv, "--ro-bind-try", "/usr", "/usr"));
        assertContainsSequence(argv, "--ro-bind-try", "/usr", "/usr");
        assertContainsSequence(argv, "--ro-bind-try", "/bin", "/bin");
        assertContainsSequence(argv, "--ro-bind-try", "/sbin", "/sbin");
        assertContainsSequence(argv, "--ro-bind-try", "/lib", "/lib");
        assertContainsSequence(argv, "--ro-bind-try", "/lib64", "/lib64");
        assertContainsSequence(argv, "--ro-bind-try", "/etc", "/etc");
        assertContainsSequence(argv, "--ro-bind-try", "/nix/store", "/nix/store");
        assertContainsSequence(argv, "--ro-bind-try", "/run/current-system/sw", "/run/current-system/sw");
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
        Files.writeString(git.resolve("HEAD"), "ref: refs/heads/main\n");
        Files.writeString(codex.resolve("config.json"), "{}");
        Files.writeString(agents.resolve("config.json"), "{}");
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
    void masksExistingEmptyProtectedMetadataDirectoryInsteadOfBindingTransientHostPath() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path codex = Files.createDirectory(workspace.resolve(".codex"));
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        ExecutionRequest request = request(cwd, policy(workspace, NetworkMode.DISABLED));

        BubblewrapCommandBuilder.BuildResult result = BubblewrapCommandBuilder.defaults()
            .buildDetailed(request, BubblewrapCommandBuilder.Options.defaults());
        List<String> argv = result.argv();

        int workspaceBind = indexOfSequence(argv, "--bind", workspace.toString(), workspace.toString());
        assertTrue(workspaceBind >= 0, "workspace must be writable");
        assertTrue(indexOfSequence(argv, "--ro-bind", codex.toString(), codex.toString()) < 0);
        assertTrue(indexOfSequence(
            argv,
            "--perms",
            "555",
            "--tmpfs",
            codex.toString(),
            "--remount-ro",
            codex.toString()
        ) > workspaceBind);
        assertTrue(result.syntheticMountTargets().stream().anyMatch(target -> codex.equals(target.path())));
    }

    @Test
    void masksExistingEmptyProtectedMetadataFileAsEmptyReadonlyFile() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path git = Files.writeString(workspace.resolve(".git"), "");
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        ExecutionRequest request = request(cwd, policy(workspace, NetworkMode.DISABLED));

        BubblewrapCommandBuilder.BuildResult result = BubblewrapCommandBuilder.defaults()
            .buildDetailed(request, BubblewrapCommandBuilder.Options.defaults());
        List<String> argv = result.argv();

        int workspaceBind = indexOfSequence(argv, "--bind", workspace.toString(), workspace.toString());
        assertTrue(workspaceBind >= 0, "workspace must be writable");
        assertTrue(indexOfSequence(argv, "--ro-bind", git.toString(), git.toString()) < 0);
        assertTrue(indexOfSequence(argv, "--ro-bind-data", "0", git.toString()) > workspaceBind);
        assertTrue(indexOfSequence(argv, "--perms", "000", "--ro-bind-data", "0", git.toString()) < 0);
        assertTrue(result.syntheticMountTargets().stream().anyMatch(target -> git.equals(target.path())));
    }

    @Test
    void masksMissingProtectedMetadataPathsAsReadonlyEmptyDirectories() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        ExecutionRequest request = request(cwd, policy(workspace, NetworkMode.DISABLED));

        List<String> argv = BubblewrapCommandBuilder.defaults().build(request);

        int workspaceBind = indexOfSequence(argv, "--bind", workspace.toString(), workspace.toString());
        assertTrue(workspaceBind >= 0, "workspace must be writable");
        assertTrue(indexOfSequence(
            argv,
            "--perms",
            "555",
            "--tmpfs",
            workspace.resolve(".git").toString(),
            "--remount-ro",
            workspace.resolve(".git").toString()
        ) > workspaceBind);
        assertTrue(indexOfSequence(
            argv,
            "--perms",
            "555",
            "--tmpfs",
            workspace.resolve(".codex").toString(),
            "--remount-ro",
            workspace.resolve(".codex").toString()
        ) > workspaceBind);
        assertTrue(indexOfSequence(
            argv,
            "--perms",
            "555",
            "--tmpfs",
            workspace.resolve(".agents").toString(),
            "--remount-ro",
            workspace.resolve(".agents").toString()
        ) > workspaceBind);
    }

    @Test
    void leavesMissingChildGitUnderParentRepoForProtectedCreateCleanup() throws Exception {
        Path repo = Files.createDirectory(tempDir.resolve("repo"));
        Path parentGit = Files.createDirectory(repo.resolve(".git"));
        Files.writeString(parentGit.resolve("HEAD"), "ref: refs/heads/main\n");
        Path workspace = Files.createDirectory(repo.resolve("workspace"));
        Path childGit = workspace.resolve(".git");
        ExecutionRequest request = request(workspace, policy(workspace, NetworkMode.DISABLED));

        BubblewrapCommandBuilder.BuildResult result = BubblewrapCommandBuilder.defaults()
            .buildDetailed(request, BubblewrapCommandBuilder.Options.defaults());
        List<String> argv = result.argv();

        assertTrue(!containsSequence(
            argv,
            "--perms",
            "555",
            "--tmpfs",
            childGit.toString(),
            "--remount-ro",
            childGit.toString()
        ));
        assertTrue(result.syntheticMountTargets().stream().noneMatch(target -> childGit.equals(target.path())));
        assertTrue(result.protectedCreateTargets().stream().anyMatch(target -> childGit.equals(target.path())));
        assertContainsSequence(
            argv,
            "--perms",
            "555",
            "--tmpfs",
            workspace.resolve(".codex").toString(),
            "--remount-ro",
            workspace.resolve(".codex").toString()
        );
        assertContainsSequence(
            argv,
            "--perms",
            "555",
            "--tmpfs",
            workspace.resolve(".agents").toString(),
            "--remount-ro",
            workspace.resolve(".agents").toString()
        );
    }

    @Test
    void deduplicatesProtectedCreateTargetsAfterReopeningWritableDescendant() throws Exception {
        Path repo = Files.createDirectory(tempDir.resolve("repo"));
        Path parentGit = Files.createDirectory(repo.resolve(".git"));
        Files.writeString(parentGit.resolve("HEAD"), "ref: refs/heads/main\n");
        Path workspace = Files.createDirectory(repo.resolve("workspace"));
        Path denied = Files.createDirectory(workspace.resolve("denied"));
        Path writableChild = Files.createDirectory(denied.resolve("child"));
        Path childGit = writableChild.resolve(".git");
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/usr")),
            List.of(denied),
            List.of(workspace, writableChild),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );

        BubblewrapCommandBuilder.BuildResult result = BubblewrapCommandBuilder.defaults()
            .buildDetailed(request(workspace, policy), BubblewrapCommandBuilder.Options.defaults());

        long childGitTargets = result.protectedCreateTargets().stream()
            .filter(target -> childGit.equals(target.path()))
            .count();
        assertEquals(1, childGitTargets);
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
        Files.writeString(git.resolve("HEAD"), "ref: refs/heads/main\n");
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
    void rejectsNonNormalizedAllowWritePathFromCustomPolicy() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        Path nonNormalizedWorkspace = workspace.resolve("..").resolve(workspace.getFileName());
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/usr")),
            List.of(),
            List.of(nonNormalizedWorkspace),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> BubblewrapCommandBuilder.defaults().build(request(cwd, policy))
        );

        assertTrue(exception.getMessage().contains("allowWrite"));
        assertTrue(exception.getMessage().contains("normalized"));
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
    void masksFirstMissingDenyReadComponentInsideWritableRoot() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path missingSecret = workspace.resolve("missing-secret").resolve("token");
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

        List<String> argv = BubblewrapCommandBuilder.defaults().build(request(cwd, policy));

        Path firstMissingComponent = workspace.resolve("missing-secret");
        int workspaceBind = indexOfSequence(argv, "--bind", workspace.toString(), workspace.toString());
        int missingMask = indexOfSequence(argv, "--perms", "000", "--ro-bind-data", "0", firstMissingComponent.toString());
        assertTrue(workspaceBind >= 0, "workspace must be writable");
        assertTrue(workspaceBind < missingMask);
        assertTrue(missingMask < argv.lastIndexOf("--"));
    }

    @Test
    void reopensWritableDirectoryUnderDenyReadAncestor() throws Exception {
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

        List<String> argv = BubblewrapCommandBuilder.defaults().build(request(cwd, policy));

        int workspaceBind = indexOfSequence(argv, "--bind", workspace.toString(), workspace.toString());
        int deniedMask = indexOfSequence(argv, "--perms", "111", "--tmpfs", denied.toString());
        int childTarget = indexOfSequence(argv, "--dir", writableChild.toString());
        int deniedReadonly = indexOfSequence(argv, "--remount-ro", denied.toString());
        int childRebind = lastIndexOfSequence(argv, "--bind", writableChild.toString(), writableChild.toString());
        assertTrue(workspaceBind >= 0, "workspace must be writable");
        assertTrue(workspaceBind < deniedMask);
        assertTrue(deniedMask < childTarget);
        assertTrue(childTarget < deniedReadonly);
        assertTrue(deniedReadonly < childRebind);
    }

    @Test
    void reopensWritableFileParentUnderDenyReadAncestor() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path denied = Files.createDirectory(workspace.resolve("denied"));
        Path childDir = Files.createDirectory(denied.resolve("child"));
        Path writableFile = Files.writeString(childDir.resolve("note.txt"), "ok");
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/usr")),
            List.of(denied),
            List.of(workspace, writableFile),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );

        List<String> argv = BubblewrapCommandBuilder.defaults().build(request(cwd, policy));

        int deniedMask = indexOfSequence(argv, "--perms", "111", "--tmpfs", denied.toString());
        int childDirTarget = indexOfSequence(argv, "--dir", childDir.toString());
        int fileTarget = indexOfSequence(argv, "--dir", writableFile.toString());
        int fileMountTarget = indexOfSequence(argv, "--file", "0", writableFile.toString());
        int deniedReadonly = indexOfSequence(argv, "--remount-ro", denied.toString());
        int fileRebind = lastIndexOfSequence(argv, "--bind", writableFile.toString(), writableFile.toString());
        assertTrue(deniedMask < childDirTarget);
        assertTrue(fileTarget < 0, "writable file itself must not be recreated as a directory");
        assertTrue(childDirTarget < deniedReadonly);
        assertTrue(childDirTarget < fileMountTarget);
        assertTrue(fileMountTarget < deniedReadonly);
        assertTrue(deniedReadonly < fileRebind);
    }

    @Test
    void reappliesProtectedMetadataReadonlyAfterReopeningWritableDescendant() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path denied = Files.createDirectory(workspace.resolve("denied"));
        Path writableChild = Files.createDirectory(denied.resolve("child"));
        Path git = Files.createDirectory(writableChild.resolve(".git"));
        Files.writeString(git.resolve("HEAD"), "ref: refs/heads/main\n");
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

        List<String> argv = BubblewrapCommandBuilder.defaults().build(request(cwd, policy));

        int deniedReadonly = indexOfSequence(argv, "--remount-ro", denied.toString());
        int childRebind = lastIndexOfSequence(argv, "--bind", writableChild.toString(), writableChild.toString());
        int gitReadonly = lastIndexOfSequence(argv, "--ro-bind", git.toString(), git.toString());
        assertTrue(deniedReadonly < childRebind);
        assertTrue(childRebind < gitReadonly);
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
            List.of(),
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

    private int lastIndexOfSequence(List<String> argv, String... sequence) {
        for (int index = argv.size() - sequence.length; index >= 0; index--) {
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
