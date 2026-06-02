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
