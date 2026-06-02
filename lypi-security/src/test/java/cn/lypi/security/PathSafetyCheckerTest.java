package cn.lypi.security;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathSafetyCheckerTest {
    @Test
    void deniesPathsEscapingCurrentWorkingDirectory() {
        PathSafetyChecker checker = new PathSafetyChecker();

        Optional<PermissionDecision> decision = checker.check(
            request("read_file", Map.of("path", "../secret.txt")),
            context(PermissionMode.BYPASS)
        );

        assertThat(decision).isPresent();
        assertThat(decision.get().behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.get().reason()).isEqualTo(PermissionDecisionReason.PATH_SAFETY);
    }

    @Test
    void deniesProtectedProjectPathsEvenInBypassMode() {
        PathSafetyChecker checker = new PathSafetyChecker();

        Optional<PermissionDecision> decision = checker.check(
            request("write_file", Map.of("path", ".git/config")),
            context(PermissionMode.BYPASS)
        );

        assertThat(decision).isPresent();
        assertThat(decision.get().behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.get().message()).contains(".git/config");
    }

    @Test
    void deniesGitPathItselfEvenInBypassMode() {
        PathSafetyChecker checker = new PathSafetyChecker();

        Optional<PermissionDecision> decision = checker.check(
            request("write_file", Map.of("path", ".git")),
            context(PermissionMode.BYPASS)
        );

        assertThat(decision).isPresent();
        assertThat(decision.get().behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.get().message()).contains(".git");
    }

    @Test
    void deniesExistingSymlinkThatEscapesCurrentWorkingDirectory(@TempDir Path tempDir) throws IOException {
        Path workspace = tempDir.resolve("workspace");
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(workspace);
        Files.createDirectories(outside);
        Files.createSymbolicLink(workspace.resolve("link-outside"), outside);
        PathSafetyChecker checker = new PathSafetyChecker();

        Optional<PermissionDecision> decision = checker.check(
            request("read_file", Map.of("path", "link-outside/secret.txt")),
            new ToolUseContext("ses_1", "msg_1", workspace, Map.of("permissionMode", PermissionMode.BYPASS))
        );

        assertThat(decision).isPresent();
        assertThat(decision.get().behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.get().reason()).isEqualTo(PermissionDecisionReason.PATH_SAFETY);
    }

    @Test
    void deniesDanglingSymlinkFileThatEscapesCurrentWorkingDirectory(@TempDir Path tempDir) throws IOException {
        Path workspace = tempDir.resolve("workspace");
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(workspace);
        Files.createDirectories(outside);
        Files.createSymbolicLink(workspace.resolve("link-file"), outside.resolve("new.txt"));
        PathSafetyChecker checker = new PathSafetyChecker();

        Optional<PermissionDecision> decision = checker.check(
            request("write_file", Map.of("path", "link-file")),
            new ToolUseContext("ses_1", "msg_1", workspace, Map.of("permissionMode", PermissionMode.BYPASS))
        );

        assertThat(decision).isPresent();
        assertThat(decision.get().behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.get().reason()).isEqualTo(PermissionDecisionReason.PATH_SAFETY);
    }

    @Test
    void deniesSymlinkThatResolvesToProtectedProjectPath(@TempDir Path tempDir) throws IOException {
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace.resolve(".git"));
        Files.createSymbolicLink(workspace.resolve("git-link"), workspace.resolve(".git"));
        PathSafetyChecker checker = new PathSafetyChecker();

        Optional<PermissionDecision> decision = checker.check(
            request("write_file", Map.of("path", "git-link/config")),
            new ToolUseContext("ses_1", "msg_1", workspace, Map.of("permissionMode", PermissionMode.BYPASS))
        );

        assertThat(decision).isPresent();
        assertThat(decision.get().behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.get().reason()).isEqualTo(PermissionDecisionReason.PATH_SAFETY);
    }

    @Test
    void deniesSymlinkParentTraversalThatEscapesCurrentWorkingDirectory(@TempDir Path tempDir) throws IOException {
        Path workspace = tempDir.resolve("workspace");
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(workspace);
        Files.createDirectories(outside);
        Files.createSymbolicLink(workspace.resolve("link-outside"), outside);
        PathSafetyChecker checker = new PathSafetyChecker();

        Optional<PermissionDecision> decision = checker.check(
            request("write_file", Map.of("path", "link-outside/../victim.txt")),
            new ToolUseContext("ses_1", "msg_1", workspace, Map.of("permissionMode", PermissionMode.BYPASS))
        );

        assertThat(decision).isPresent();
        assertThat(decision.get().behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.get().reason()).isEqualTo(PermissionDecisionReason.PATH_SAFETY);
    }

    @Test
    void deniesNestedSymlinkChainThatEscapesCurrentWorkingDirectory(@TempDir Path tempDir) throws IOException {
        Path workspace = tempDir.resolve("workspace");
        Path safe = workspace.resolve("safe");
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(safe);
        Files.createDirectories(outside);
        Files.createSymbolicLink(workspace.resolve("link-in"), safe);
        Files.createSymbolicLink(safe.resolve("link-out"), outside);
        PathSafetyChecker checker = new PathSafetyChecker();

        Optional<PermissionDecision> decision = checker.check(
            request("read_file", Map.of("path", "link-in/link-out/secret.txt")),
            new ToolUseContext("ses_1", "msg_1", workspace, Map.of("permissionMode", PermissionMode.BYPASS))
        );

        assertThat(decision).isPresent();
        assertThat(decision.get().behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.get().reason()).isEqualTo(PermissionDecisionReason.PATH_SAFETY);
    }

    @Test
    void deniesSymlinkToSymlinkChainThatEscapesCurrentWorkingDirectory(@TempDir Path tempDir) throws IOException {
        Path workspace = tempDir.resolve("workspace");
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(workspace);
        Files.createDirectories(outside);
        Files.createSymbolicLink(workspace.resolve("link-a"), Path.of("link-b"));
        Files.createSymbolicLink(workspace.resolve("link-b"), outside);
        PathSafetyChecker checker = new PathSafetyChecker();

        Optional<PermissionDecision> decision = checker.check(
            request("read_file", Map.of("path", "link-a/secret.txt")),
            new ToolUseContext("ses_1", "msg_1", workspace, Map.of("permissionMode", PermissionMode.BYPASS))
        );

        assertThat(decision).isPresent();
        assertThat(decision.get().behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.get().reason()).isEqualTo(PermissionDecisionReason.PATH_SAFETY);
    }

    @Test
    void allowsNormalChildPathWhenCurrentWorkingDirectoryIsSymlink(@TempDir Path tempDir) throws IOException {
        Path realWorkspace = tempDir.resolve("real-workspace");
        Path linkedWorkspace = tempDir.resolve("linked-workspace");
        Files.createDirectories(realWorkspace);
        Files.createFile(realWorkspace.resolve("notes.txt"));
        Files.createSymbolicLink(linkedWorkspace, realWorkspace);
        PathSafetyChecker checker = new PathSafetyChecker();

        Optional<PermissionDecision> decision = checker.check(
            request("read_file", Map.of("path", "notes.txt")),
            new ToolUseContext("ses_1", "msg_1", linkedWorkspace, Map.of("permissionMode", PermissionMode.BYPASS))
        );

        assertThat(decision).isEmpty();
    }

    @Test
    void checksCommonPathFieldsForEditLikeTools() {
        PathSafetyChecker checker = new PathSafetyChecker();

        Optional<PermissionDecision> decision = checker.check(
            request("edit_file", Map.of("filePath", "../outside.txt")),
            context(PermissionMode.DEFAULT_EXECUTE)
        );

        assertThat(decision).isPresent();
        assertThat(decision.get().behavior()).isEqualTo(PermissionBehavior.DENY);
    }

    private ToolUseRequest request(String toolName, Map<String, Object> input) {
        return new ToolUseRequest("toolu_1", toolName, input, "msg_1");
    }

    private ToolUseContext context(PermissionMode mode) {
        return new ToolUseContext("ses_1", "msg_1", Path.of("/workspace"), Map.of("permissionMode", mode));
    }
}
