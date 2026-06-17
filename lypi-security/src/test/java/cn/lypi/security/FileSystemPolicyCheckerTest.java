package cn.lypi.security;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.security.FileSystemAccessMode;
import cn.lypi.contracts.security.FileSystemPath;
import cn.lypi.contracts.security.FileSystemPermissionEntry;
import cn.lypi.contracts.security.FileSystemPermissionPolicy;
import cn.lypi.contracts.security.FileSystemSpecialPath;
import cn.lypi.contracts.security.ManagedPermissionProfile;
import cn.lypi.contracts.security.NetworkPermissionPolicy;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionProfiles;
import cn.lypi.contracts.tool.ToolUseContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemPolicyCheckerTest {
    private final FileSystemPolicyChecker checker = new FileSystemPolicyChecker();

    @Test
    void readOnlyProfileDeniesWritesInsideWorkspace(@TempDir Path workspace) {
        PermissionDecision decision = checker.decide(
            PermissionProfiles.readOnly(),
            FileSystemAccessMode.WRITE,
            workspace.resolve("notes.txt"),
            context(workspace)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.SANDBOX_POLICY);
    }

    @Test
    void workspaceProfileAllowsWritesInsideWorkspace(@TempDir Path workspace) {
        PermissionDecision decision = checker.decide(
            PermissionProfiles.workspace(),
            FileSystemAccessMode.WRITE,
            workspace.resolve("src/App.java"),
            context(workspace)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.SANDBOX_POLICY);
    }

    @Test
    void writeEntryAllowsReadAccess(@TempDir Path workspace) {
        PermissionDecision decision = checker.decide(
            PermissionProfiles.workspace(),
            FileSystemAccessMode.READ,
            workspace.resolve("src/App.java"),
            context(workspace)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ALLOW);
    }

    @Test
    void denyEntryWinsOverAllowEntry(@TempDir Path workspace) {
        ManagedPermissionProfile profile = new ManagedPermissionProfile(
            FileSystemPermissionPolicy.restricted(List.of(
                new FileSystemPermissionEntry(FileSystemPath.special(FileSystemSpecialPath.PROJECT_ROOTS), FileSystemAccessMode.WRITE),
                new FileSystemPermissionEntry(FileSystemPath.exactPath("secret.txt"), FileSystemAccessMode.DENY)
            )),
            NetworkPermissionPolicy.restricted()
        );

        PermissionDecision decision = checker.decide(
            profile,
            FileSystemAccessMode.WRITE,
            workspace.resolve("secret.txt"),
            context(workspace)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.DENY);
    }

    @Test
    void globPatternMatchesRelativePathNotBasenameEverywhere(@TempDir Path workspace) {
        ManagedPermissionProfile profile = new ManagedPermissionProfile(
            FileSystemPermissionPolicy.restricted(List.of(
                new FileSystemPermissionEntry(FileSystemPath.globPattern("logs/*.log"), FileSystemAccessMode.READ)
            )),
            NetworkPermissionPolicy.restricted()
        );

        PermissionDecision allowed = checker.decide(
            profile,
            FileSystemAccessMode.READ,
            workspace.resolve("logs/app.log"),
            context(workspace)
        );
        PermissionDecision denied = checker.decide(
            profile,
            FileSystemAccessMode.READ,
            workspace.resolve("tmp/app.log"),
            context(workspace)
        );

        assertThat(allowed.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(denied.behavior()).isEqualTo(PermissionBehavior.DENY);
    }

    @Test
    void exactPathMatchesRealCurrentWorkingDirectoryCandidate(@TempDir Path tempDir) throws IOException {
        Path realWorkspace = tempDir.resolve("real-workspace");
        Path linkedWorkspace = tempDir.resolve("linked-workspace");
        Files.createDirectories(realWorkspace.resolve("src"));
        Files.createSymbolicLink(linkedWorkspace, realWorkspace);
        ManagedPermissionProfile profile = new ManagedPermissionProfile(
            FileSystemPermissionPolicy.restricted(List.of(
                new FileSystemPermissionEntry(FileSystemPath.exactPath("src"), FileSystemAccessMode.WRITE)
            )),
            NetworkPermissionPolicy.restricted()
        );

        PermissionDecision decision = checker.decide(
            profile,
            FileSystemAccessMode.WRITE,
            linkedWorkspace.resolve("src/App.java"),
            context(linkedWorkspace)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ALLOW);
    }

    @Test
    void globPatternMatchesRealCurrentWorkingDirectoryCandidate(@TempDir Path tempDir) throws IOException {
        Path realWorkspace = tempDir.resolve("real-workspace");
        Path linkedWorkspace = tempDir.resolve("linked-workspace");
        Files.createDirectories(realWorkspace.resolve("logs"));
        Files.createSymbolicLink(linkedWorkspace, realWorkspace);
        ManagedPermissionProfile profile = new ManagedPermissionProfile(
            FileSystemPermissionPolicy.restricted(List.of(
                new FileSystemPermissionEntry(FileSystemPath.globPattern("logs/*.log"), FileSystemAccessMode.READ)
            )),
            NetworkPermissionPolicy.restricted()
        );

        PermissionDecision decision = checker.decide(
            profile,
            FileSystemAccessMode.READ,
            linkedWorkspace.resolve("logs/app.log"),
            context(linkedWorkspace)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ALLOW);
    }

    @Test
    void matchesRealPathCandidateWhenCurrentWorkingDirectoryIsSymlink(@TempDir Path tempDir) throws IOException {
        Path realWorkspace = tempDir.resolve("real-workspace");
        Path linkedWorkspace = tempDir.resolve("linked-workspace");
        Files.createDirectories(realWorkspace.resolve("src"));
        Files.createSymbolicLink(linkedWorkspace, realWorkspace);

        PermissionDecision decision = checker.decide(
            projectRootsWriteOnlyProfile(),
            FileSystemAccessMode.WRITE,
            linkedWorkspace.resolve("src/App.java"),
            context(linkedWorkspace)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ALLOW);
    }

    @Test
    void workspaceProfileDeniesSymlinkResolvedOutsideWorkspace(@TempDir Path tempDir) throws IOException {
        Path workspace = tempDir.resolve("workspace");
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(workspace);
        Files.createDirectories(outside);
        Files.createSymbolicLink(workspace.resolve("link-outside"), outside);

        PermissionDecision decision = checker.decide(
            projectRootsWriteOnlyProfile(),
            FileSystemAccessMode.WRITE,
            workspace.resolve("link-outside/secret.txt"),
            context(workspace)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.DENY);
    }

    @Test
    void workspaceProfileDeniesDanglingSymlinkResolvedOutsideWorkspace(@TempDir Path tempDir) throws IOException {
        Path workspace = tempDir.resolve("workspace");
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(workspace);
        Files.createDirectories(outside);
        Files.createSymbolicLink(workspace.resolve("link-file"), outside.resolve("new.txt"));

        PermissionDecision decision = checker.decide(
            projectRootsWriteOnlyProfile(),
            FileSystemAccessMode.WRITE,
            workspace.resolve("link-file"),
            context(workspace)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.DENY);
    }

    @Test
    void hardSafetyStillProtectsMetadataInDangerFullAccess(@TempDir Path workspace) {
        Optional<PermissionDecision> hardSafety = new PathSafetyChecker().checkPath(
            "path",
            ".git/config",
            context(workspace)
        );

        assertThat(hardSafety).isPresent();
        assertThat(hardSafety.get().behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(hardSafety.get().reason()).isEqualTo(PermissionDecisionReason.PATH_SAFETY);
    }

    @Test
    void externalProfileDoesNotAllowFilesystemInsideInternalChecker(@TempDir Path workspace) {
        PermissionDecision decision = checker.decide(
            PermissionProfiles.external(NetworkPermissionPolicy.enabled()),
            FileSystemAccessMode.WRITE,
            workspace.resolve("notes.txt"),
            context(workspace)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.message()).contains("external");
    }

    private ToolUseContext context(Path workspace) {
        return new ToolUseContext(
            "ses_1",
            "msg_1",
            workspace,
            Map.of("permissionMode", PermissionMode.BYPASS)
        );
    }

    private ManagedPermissionProfile projectRootsWriteOnlyProfile() {
        return new ManagedPermissionProfile(
            FileSystemPermissionPolicy.restricted(List.of(
                new FileSystemPermissionEntry(FileSystemPath.special(FileSystemSpecialPath.PROJECT_ROOTS), FileSystemAccessMode.WRITE)
            )),
            NetworkPermissionPolicy.restricted()
        );
    }
}
