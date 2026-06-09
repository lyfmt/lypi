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
    void usesReadonlyFullRootWhenAllowReadContainsRoot() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/")),
            List.of(),
            List.of(workspace),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );

        List<String> argv = BubblewrapCommandBuilder.defaults().build(request(cwd, policy));

        int readonlyRoot = indexOfSequence(argv, "--ro-bind", "/", "/");
        assertTrue(readonlyRoot >= 0, "full-read policy must mount / as read-only root");
        assertTrue(!containsSequence(argv, "--tmpfs", "/"));
        assertTrue(readonlyRoot < indexOfSequence(argv, "--dev", "/dev"));
        assertTrue(readonlyRoot < indexOfSequence(argv, "--tmpfs", "/tmp"));
        assertTrue(readonlyRoot < indexOfSequence(argv, "--bind", workspace.toString(), workspace.toString()));
        assertTrue(!containsSequence(argv, "--ro-bind-try", "/usr", "/usr"));
        assertContainsSequence(argv, "--dev", "/dev");
        assertContainsSequence(argv, "--tmpfs", "/tmp");
        assertContainsSequence(argv, "--bind", workspace.toString(), workspace.toString());
        assertContainsSequence(argv, "--chdir", cwd.toString());
    }

    @Test
    void validatesAllAllowReadPathsEvenWhenRootIsAllowed() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/"), Path.of("relative-read")),
            List.of(),
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

        assertTrue(exception.getMessage().contains("allowRead"));
        assertTrue(exception.getMessage().contains("absolute"));
    }

    @Test
    void skipsMissingWritableRoots() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path missing = tempDir.resolve("missing-workspace");
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/usr")),
            List.of(),
            List.of(workspace, missing),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );

        List<String> argv = BubblewrapCommandBuilder.defaults().build(request(workspace, policy));

        assertContainsSequence(argv, "--bind", workspace.toString(), workspace.toString());
        assertTrue(!containsSequence(argv, "--bind", missing.toString(), missing.toString()));
        assertTrue(!argv.contains(missing.toString()));
    }

    @Test
    void bindsWritableRootsFromParentToChildRegardlessOfPolicyOrder() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path child = Files.createDirectory(workspace.resolve("child"));
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/usr")),
            List.of(),
            List.of(child, workspace),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );

        List<String> argv = BubblewrapCommandBuilder.defaults().build(request(workspace, policy));

        int workspaceBind = indexOfSequence(argv, "--bind", workspace.toString(), workspace.toString());
        int childBind = indexOfSequence(argv, "--bind", child.toString(), child.toString());
        assertTrue(workspaceBind >= 0, "workspace must be writable");
        assertTrue(childBind >= 0, "child must be writable");
        assertTrue(workspaceBind < childBind, "parent writable root must be bound before child writable root");
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
    void rejectsAllowWriteProtectedMetadataSymlinkRoot() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path realGit = Files.createDirectory(tempDir.resolve("realgit"));
        Path git = workspace.resolve(".git");
        Files.createSymbolicLink(git, realGit);
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/usr")),
            List.of(),
            List.of(git),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> BubblewrapCommandBuilder.defaults().build(request(cwd, policy))
        );

        assertTrue(exception.getMessage().contains("protected metadata"));
        assertTrue(exception.getMessage().contains("symbolic link"));
    }

    @Test
    void rejectsAllowWriteProtectedMetadataDescendant() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path git = Files.createDirectory(workspace.resolve(".git"));
        Path config = Files.writeString(git.resolve("config"), "secret");
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/usr")),
            List.of(),
            List.of(config),
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
        assertTrue(exception.getMessage().contains("protected metadata"));
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
    void bindsCanonicalTargetForAllowWriteSymlinkAndCanonicalizesCwd() throws Exception {
        Path realWorkspace = Files.createDirectory(tempDir.resolve("real-workspace"));
        Path workspaceLink = tempDir.resolve("workspace-link");
        Files.createSymbolicLink(workspaceLink, realWorkspace);
        Path realCwd = Files.createDirectory(realWorkspace.resolve("src"));
        Path linkCwd = workspaceLink.resolve("src");
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/usr")),
            List.of(),
            List.of(workspaceLink),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );

        List<String> argv = BubblewrapCommandBuilder.defaults().build(request(linkCwd, policy));

        assertContainsSequence(argv, "--bind", realWorkspace.toString(), realWorkspace.toString());
        assertTrue(!containsSequence(argv, "--bind", workspaceLink.toString(), workspaceLink.toString()));
        assertContainsSequence(argv, "--chdir", realCwd.toString());
        assertTrue(!argv.contains(linkCwd.toString()));
    }

    @Test
    void rejectsDenyReadUnderSymlinkWritableRootAlias() throws Exception {
        Path realWorkspace = Files.createDirectory(tempDir.resolve("real-workspace"));
        Path workspaceLink = tempDir.resolve("workspace-link");
        Files.createSymbolicLink(workspaceLink, realWorkspace);
        Path secret = Files.createDirectory(realWorkspace.resolve("secret"));
        Files.createDirectory(realWorkspace.resolve("src"));
        Path linkCwd = workspaceLink.resolve("src");
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(workspaceLink),
            List.of(workspaceLink.resolve(secret.getFileName())),
            List.of(workspaceLink),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> BubblewrapCommandBuilder.defaults().build(request(linkCwd, policy))
        );

        assertTrue(exception.getMessage().contains("denyRead path must not cross a symbolic link"));
    }

    @Test
    void rejectsAllowReadThroughSymlinkInsideWritableParent() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path realTarget = Files.createDirectory(tempDir.resolve("real-target"));
        Path secret = Files.writeString(realTarget.resolve("secret.txt"), "secret");
        Path link = workspace.resolve("link");
        Files.createSymbolicLink(link, realTarget);
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/usr"), link.resolve(secret.getFileName())),
            List.of(),
            List.of(workspace, link),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> BubblewrapCommandBuilder.defaults().build(request(cwd, policy))
        );

        assertTrue(exception.getMessage().contains("allowRead path must not cross a writable symbolic link"));
        assertTrue(exception.getMessage().contains(link.toString()));
    }

    @Test
    void rejectsAllowWriteSymlinkTargetInsideProtectedMetadata() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path git = Files.createDirectory(workspace.resolve(".git"));
        Path config = Files.writeString(git.resolve("config"), "secret");
        Path configLink = workspace.resolve("config-link");
        Files.createSymbolicLink(configLink, config);
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/usr")),
            List.of(),
            List.of(configLink),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> BubblewrapCommandBuilder.defaults().build(request(cwd, policy))
        );

        assertTrue(exception.getMessage().contains("protected metadata"));
        assertTrue(exception.getMessage().contains("symbolic link target"));
    }

    @Test
    void remapsAllowReadUnderSymlinkWritableRootToCanonicalTarget() throws Exception {
        Path realWorkspace = Files.createDirectory(tempDir.resolve("real-workspace"));
        Path workspaceLink = tempDir.resolve("workspace-link");
        Files.createSymbolicLink(workspaceLink, realWorkspace);
        Path secret = Files.createDirectory(realWorkspace.resolve("secret"));
        Path realCwd = Files.createDirectory(realWorkspace.resolve("src"));
        Path linkCwd = workspaceLink.resolve("src");
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(workspaceLink),
            List.of(secret),
            List.of(workspaceLink),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );

        List<String> argv = BubblewrapCommandBuilder.defaults().build(request(linkCwd, policy));

        assertContainsSequence(argv, "--ro-bind-try", realWorkspace.toString(), realWorkspace.toString());
        assertTrue(!argv.contains(workspaceLink.toString()));
        assertContainsSequence(argv, "--bind", realWorkspace.toString(), realWorkspace.toString());
        assertContainsSequence(argv, "--perms", "000", "--tmpfs", secret.toString(), "--remount-ro", secret.toString());
        assertContainsSequence(argv, "--chdir", realCwd.toString());
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
    void rejectsMissingDenyReadBelowExistingFileAncestor() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path token = Files.writeString(workspace.resolve("token"), "secret");
        Path missingSecret = token.resolve("secret");
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

        assertTrue(exception.getMessage().contains("denyRead"));
        assertTrue(exception.getMessage().contains("parent must be a directory"));
        assertTrue(exception.getMessage().contains(token.toString()));
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
    void reappliesAllowReadInsideWritableDescendantAfterDenyReadRebind() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path denied = Files.createDirectory(workspace.resolve("denied"));
        Path writableChild = Files.createDirectory(denied.resolve("child"));
        Path missingReadOnly = writableChild.resolve("generated").resolve("config.json");
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/usr"), missingReadOnly),
            List.of(denied),
            List.of(workspace, writableChild),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );

        BubblewrapCommandBuilder.BuildResult result = BubblewrapCommandBuilder.defaults()
            .buildDetailed(request(cwd, policy), BubblewrapCommandBuilder.Options.defaults());
        List<String> argv = result.argv();
        Path firstMissingComponent = writableChild.resolve("generated");

        int childRebind = lastIndexOfSequence(argv, "--bind", writableChild.toString(), writableChild.toString());
        int missingMask = lastIndexOfSequence(argv, "--ro-bind-data", "0", firstMissingComponent.toString());
        assertTrue(childRebind > indexOfSequence(argv, "--remount-ro", denied.toString()));
        assertTrue(missingMask > childRebind, "allowRead mask must be restored after denyRead reopens writable child");
        assertTrue(result.syntheticMountTargets().stream().anyMatch(target -> firstMissingComponent.equals(target.path())));
    }

    @Test
    void deduplicatesSharedMissingAllowReadMaskAfterDenyReadRebind() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path denied = Files.createDirectory(workspace.resolve("denied"));
        Path writableChild = Files.createDirectory(denied.resolve("child"));
        Path missingDirectory = writableChild.resolve("generated");
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/usr"), missingDirectory.resolve("a.json"), missingDirectory.resolve("b.json")),
            List.of(denied),
            List.of(workspace, writableChild),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );

        List<String> argv = BubblewrapCommandBuilder.defaults().build(request(cwd, policy));

        int childRebind = lastIndexOfSequence(argv, "--bind", writableChild.toString(), writableChild.toString());
        int firstMask = indexOfSequence(argv, "--ro-bind-data", "0", missingDirectory.toString());
        int lastMask = lastIndexOfSequence(argv, "--ro-bind-data", "0", missingDirectory.toString());
        assertTrue(firstMask > 0);
        assertTrue(lastMask > childRebind, "allowRead mask must be restored after denyRead reopens writable child");
        assertEquals(2, countSequence(argv, "--ro-bind-data", "0", missingDirectory.toString()));
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
    void reappliesAllowReadUnderWritableRootBeforeNestedWritableBind() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path docs = Files.createDirectory(workspace.resolve("docs"));
        Path publicDocs = Files.createDirectory(docs.resolve("public"));
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/usr"), docs),
            List.of(),
            List.of(workspace, publicDocs),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );

        List<String> argv = BubblewrapCommandBuilder.defaults().build(request(cwd, policy));

        int workspaceBind = indexOfSequence(argv, "--bind", workspace.toString(), workspace.toString());
        int docsReadonly = lastIndexOfSequence(argv, "--ro-bind", docs.toString(), docs.toString());
        int publicDocsBind = lastIndexOfSequence(argv, "--bind", publicDocs.toString(), publicDocs.toString());
        assertTrue(workspaceBind >= 0, "workspace must be writable");
        assertTrue(docsReadonly > workspaceBind, "allowRead child must be reapplied after writable workspace bind");
        assertTrue(publicDocsBind > docsReadonly, "nested writable child must be rebound after read-only parent");
    }

    @Test
    void masksMissingAllowReadUnderWritableRoot() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path missingReadOnly = workspace.resolve("generated").resolve("config.json");
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/usr"), missingReadOnly),
            List.of(),
            List.of(workspace),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );

        BubblewrapCommandBuilder.BuildResult result = BubblewrapCommandBuilder.defaults()
            .buildDetailed(request(cwd, policy), BubblewrapCommandBuilder.Options.defaults());
        List<String> argv = result.argv();
        Path firstMissingComponent = workspace.resolve("generated");

        int workspaceBind = indexOfSequence(argv, "--bind", workspace.toString(), workspace.toString());
        int missingMask = indexOfSequence(argv, "--ro-bind-data", "0", firstMissingComponent.toString());
        assertTrue(workspaceBind >= 0, "workspace must be writable");
        assertTrue(missingMask > workspaceBind, "missing allowRead path must be masked after writable bind");
        assertTrue(result.syntheticMountTargets().stream().anyMatch(target -> firstMissingComponent.equals(target.path())));
    }

    @Test
    void rejectsMissingAllowReadBelowExistingFileAncestor() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path token = Files.writeString(workspace.resolve("token"), "secret");
        Path missingReadOnly = token.resolve("config.json");
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/usr"), missingReadOnly),
            List.of(),
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

        assertTrue(exception.getMessage().contains("allowRead"));
        assertTrue(exception.getMessage().contains("parent must be a directory"));
        assertTrue(exception.getMessage().contains(token.toString()));
    }

    @Test
    void deduplicatesSharedMissingAllowReadMaskUnderWritableRoot() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path missingDirectory = workspace.resolve("generated");
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/usr"), missingDirectory.resolve("a.json"), missingDirectory.resolve("b.json")),
            List.of(),
            List.of(workspace),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );

        List<String> argv = BubblewrapCommandBuilder.defaults().build(request(cwd, policy));

        int workspaceBind = indexOfSequence(argv, "--bind", workspace.toString(), workspace.toString());
        int missingMask = indexOfSequence(argv, "--ro-bind-data", "0", missingDirectory.toString());
        assertTrue(workspaceBind >= 0, "workspace must be writable");
        assertTrue(missingMask > workspaceBind, "missing allowRead path must be masked after writable bind");
        assertEquals(1, countSequence(argv, "--ro-bind-data", "0", missingDirectory.toString()));
    }

    @Test
    void masksMissingAllowReadEvenWhenWritableRootItselfIsReadable() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path missingReadOnly = workspace.resolve("generated").resolve("config.json");
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/usr"), workspace, missingReadOnly),
            List.of(),
            List.of(workspace),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );

        BubblewrapCommandBuilder.BuildResult result = BubblewrapCommandBuilder.defaults()
            .buildDetailed(request(cwd, policy), BubblewrapCommandBuilder.Options.defaults());
        List<String> argv = result.argv();
        Path firstMissingComponent = workspace.resolve("generated");

        int workspaceBind = indexOfSequence(argv, "--bind", workspace.toString(), workspace.toString());
        int missingMask = indexOfSequence(argv, "--ro-bind-data", "0", firstMissingComponent.toString());
        assertTrue(workspaceBind >= 0, "workspace must be writable");
        assertTrue(missingMask > workspaceBind, "writable root allowRead entry must not hide deeper missing carveout");
        assertTrue(result.syntheticMountTargets().stream().anyMatch(target -> firstMissingComponent.equals(target.path())));
    }

    @Test
    void skipsMissingAllowReadUnderExistingReadonlyAncestor() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path docs = Files.createDirectory(workspace.resolve("docs"));
        Path missingReadOnly = docs.resolve("generated").resolve("config.json");
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/usr"), docs, missingReadOnly),
            List.of(),
            List.of(workspace),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );

        BubblewrapCommandBuilder.BuildResult result = BubblewrapCommandBuilder.defaults()
            .buildDetailed(request(cwd, policy), BubblewrapCommandBuilder.Options.defaults());
        List<String> argv = result.argv();
        Path firstMissingComponent = docs.resolve("generated");

        int docsReadonly = lastIndexOfSequence(argv, "--ro-bind", docs.toString(), docs.toString());
        assertTrue(docsReadonly > indexOfSequence(argv, "--bind", workspace.toString(), workspace.toString()));
        assertTrue(!containsSequence(argv, "--ro-bind-data", "0", firstMissingComponent.toString()));
        assertTrue(result.syntheticMountTargets().stream().noneMatch(target -> firstMissingComponent.equals(target.path())));
    }

    @Test
    void reappliesAllowReadInsideNestedWritableRootDespiteReadonlyAncestor() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path docs = Files.createDirectory(workspace.resolve("docs"));
        Path publicDocs = Files.createDirectory(docs.resolve("public"));
        Path secret = Files.writeString(publicDocs.resolve("secret.txt"), "secret");
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/usr"), docs, secret),
            List.of(),
            List.of(workspace, publicDocs),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );

        List<String> argv = BubblewrapCommandBuilder.defaults().build(request(cwd, policy));

        int docsReadonly = lastIndexOfSequence(argv, "--ro-bind", docs.toString(), docs.toString());
        int publicDocsBind = lastIndexOfSequence(argv, "--bind", publicDocs.toString(), publicDocs.toString());
        int secretReadonly = lastIndexOfSequence(argv, "--ro-bind", secret.toString(), secret.toString());
        assertTrue(docsReadonly > indexOfSequence(argv, "--bind", workspace.toString(), workspace.toString()));
        assertTrue(publicDocsBind > docsReadonly);
        assertTrue(secretReadonly > publicDocsBind);
    }

    @Test
    void masksMissingAllowReadInsideNestedWritableRootDespiteReadonlyAncestor() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path docs = Files.createDirectory(workspace.resolve("docs"));
        Path publicDocs = Files.createDirectory(docs.resolve("public"));
        Path missingReadOnly = publicDocs.resolve("generated").resolve("config.json");
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/usr"), docs, missingReadOnly),
            List.of(),
            List.of(workspace, publicDocs),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );

        BubblewrapCommandBuilder.BuildResult result = BubblewrapCommandBuilder.defaults()
            .buildDetailed(request(cwd, policy), BubblewrapCommandBuilder.Options.defaults());
        List<String> argv = result.argv();
        Path firstMissingComponent = publicDocs.resolve("generated");

        int publicDocsBind = lastIndexOfSequence(argv, "--bind", publicDocs.toString(), publicDocs.toString());
        int missingMask = indexOfSequence(argv, "--ro-bind-data", "0", firstMissingComponent.toString());
        assertTrue(publicDocsBind > lastIndexOfSequence(argv, "--ro-bind", docs.toString(), docs.toString()));
        assertTrue(missingMask > publicDocsBind);
        assertTrue(result.syntheticMountTargets().stream().anyMatch(target -> firstMissingComponent.equals(target.path())));
    }

    @Test
    void masksMissingAllowReadProtectedMetadataAsReadonlyDirectory() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path git = workspace.resolve(".git");
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/usr"), git),
            List.of(),
            List.of(workspace),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );

        BubblewrapCommandBuilder.BuildResult result = BubblewrapCommandBuilder.defaults()
            .buildDetailed(request(cwd, policy), BubblewrapCommandBuilder.Options.defaults());
        List<String> argv = result.argv();

        assertTrue(!containsSequence(argv, "--ro-bind-data", "0", git.toString()));
        assertContainsSequence(
            argv,
            "--perms",
            "555",
            "--tmpfs",
            git.toString(),
            "--remount-ro",
            git.toString()
        );
        assertEquals(
            indexOfSequence(argv, "--perms", "555", "--tmpfs", git.toString(), "--remount-ro", git.toString()),
            lastIndexOfSequence(argv, "--perms", "555", "--tmpfs", git.toString(), "--remount-ro", git.toString())
        );
        assertTrue(result.syntheticMountTargets().stream().anyMatch(target -> git.equals(target.path())));
    }

    @Test
    void skipsMissingAllowReadUnderProtectedMetadata() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path git = Files.createDirectory(workspace.resolve(".git"));
        Files.writeString(git.resolve("HEAD"), "ref: refs/heads/main\n");
        Path missingConfig = git.resolve("config");
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/usr"), missingConfig),
            List.of(),
            List.of(workspace),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );

        BubblewrapCommandBuilder.BuildResult result = BubblewrapCommandBuilder.defaults()
            .buildDetailed(request(cwd, policy), BubblewrapCommandBuilder.Options.defaults());
        List<String> argv = result.argv();

        assertTrue(!containsSequence(argv, "--ro-bind-data", "0", missingConfig.toString()));
        assertTrue(result.syntheticMountTargets().stream().noneMatch(target -> missingConfig.equals(target.path())));
        assertTrue(indexOfSequence(argv, "--ro-bind", git.toString(), git.toString()) > indexOfSequence(
            argv,
            "--bind",
            workspace.toString(),
            workspace.toString()
        ));
    }

    @Test
    void masksMissingNestedProtectedMetadataAllowReadWhenNotCoveredByProtectedRoot() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path pkg = Files.createDirectory(workspace.resolve("pkg"));
        Path nestedGit = pkg.resolve(".git");
        Path missingConfig = nestedGit.resolve("config");
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/usr"), missingConfig),
            List.of(),
            List.of(workspace),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );

        BubblewrapCommandBuilder.BuildResult result = BubblewrapCommandBuilder.defaults()
            .buildDetailed(request(cwd, policy), BubblewrapCommandBuilder.Options.defaults());
        List<String> argv = result.argv();

        int workspaceBind = indexOfSequence(argv, "--bind", workspace.toString(), workspace.toString());
        int nestedGitMask = indexOfSequence(argv, "--ro-bind-data", "0", nestedGit.toString());
        assertTrue(workspaceBind >= 0, "workspace must be writable");
        assertTrue(nestedGitMask > workspaceBind, "nested metadata path is not covered by root metadata masks");
        assertTrue(result.syntheticMountTargets().stream().anyMatch(target -> nestedGit.equals(target.path())));
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

    private int countSequence(List<String> argv, String... sequence) {
        int count = 0;
        for (int index = 0; index <= argv.size() - sequence.length; index++) {
            boolean matches = true;
            for (int offset = 0; offset < sequence.length; offset++) {
                matches = matches && sequence[offset].equals(argv.get(index + offset));
            }
            if (matches) {
                count++;
            }
        }
        return count;
    }

    private void assertCommandSuffix(List<String> argv, List<String> command) {
        int separator = argv.lastIndexOf("--");
        assertTrue(separator >= 0, "bwrap argv must contain -- separator");
        assertEquals(command, argv.subList(separator + 1, argv.size()));
    }
}
