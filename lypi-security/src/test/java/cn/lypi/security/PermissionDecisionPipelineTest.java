package cn.lypi.security;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.security.AdditionalPermissionProfile;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.FileSystemAccessMode;
import cn.lypi.contracts.security.FileSystemPath;
import cn.lypi.contracts.security.FileSystemPermissionEntry;
import cn.lypi.contracts.security.FileSystemPermissionPolicy;
import cn.lypi.contracts.security.FileSystemSpecialPath;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.security.PermissionRuleSource;
import cn.lypi.contracts.security.PermissionRuleValue;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PermissionDecisionPipelineTest {
    @Test
    void hardSafetyRunsBeforeExplicitAllow() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline(List.of(
            rule(PermissionBehavior.ALLOW, "read_file", "*", "allow reads")
        ));

        PermissionDecision decision = pipeline.decide(
            request("read_file", Map.of("path", ".git/config")),
            context(PermissionMode.BYPASS)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.HARD_SAFETY);
    }

    @Test
    void planModeDeniesSandboxEscalationBeforeModeDefault() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();

        PermissionDecision decision = pipeline.decide(
            request("bash", Map.of(
                "command", "id",
                "sandboxPermissions", "requireEscalated",
                "justification", "needs host namespace"
            )),
            context(PermissionMode.BYPASS, Map.of("agentMode", AgentMode.PLAN))
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.SANDBOX_POLICY);
        assertThat(decision.message()).contains("AgentMode.PLAN");
    }

    @Test
    void planModeDeniesSandboxEscalationBeforeHardSafety() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();

        PermissionDecision decision = pipeline.decide(
            request("bash", Map.of(
                "command", "id",
                "cwd", ".git",
                "sandboxPermissions", "requireEscalated",
                "justification", "needs host namespace"
            )),
            context(PermissionMode.BYPASS, Map.of("agentMode", AgentMode.PLAN))
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.SANDBOX_POLICY);
        assertThat(decision.message()).contains("AgentMode.PLAN");
    }

    @Test
    void planModeDeniesRequestPermissionsTool() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();

        PermissionDecision decision = pipeline.decide(
            request("request_permissions", Map.of("reason", "need write access")),
            context(PermissionMode.BYPASS, Map.of("agentMode", AgentMode.PLAN))
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.SANDBOX_POLICY);
        assertThat(decision.message()).contains("AgentMode.PLAN");
    }

    @Test
    void planModeDeniesAdditionalPermissionsSandboxOverride() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();

        PermissionDecision decision = pipeline.decide(
            request("bash", Map.of(
                "command", "touch cache/out",
                "sandboxPermissions", "withAdditionalPermissions",
                "additionalPermissions", Map.of()
            )),
            context(PermissionMode.BYPASS, Map.of("agentMode", AgentMode.PLAN))
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.SANDBOX_POLICY);
        assertThat(decision.message()).contains("AgentMode.PLAN");
    }

    @Test
    void unknownBashAsksEvenWhenBypassWouldAllow() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();

        PermissionDecision decision = pipeline.decide(
            request("bash", Map.of("command", "bash -c \"$(cat script.sh)\"")),
            context(PermissionMode.BYPASS)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ASK);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.BASH_RISK);
    }

    @Test
    void strictAutoReviewDoesNotOverrideBashRiskAsk() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();

        PermissionDecision decision = pipeline.decide(
            request("bash", Map.of("command", "bash -c \"$(cat script.sh)\"")),
            context(PermissionMode.BYPASS, Map.of("strictAutoReview", true))
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ASK);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.BASH_RISK);
        assertThat(decision.metadata()).containsKey("bashRisk");
    }

    @Test
    void explicitDenyRunsBeforeOtherReviewStages() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline(List.of(
            rule(PermissionBehavior.ALLOW, "bash", "prefix:git status", "allow status"),
            rule(PermissionBehavior.DENY, "bash", "git status *", "deny status")
        ));

        PermissionDecision decision = pipeline.decide(
            request("bash", Map.of("command", "git status --short")),
            context(PermissionMode.BYPASS, Map.of("strictAutoReview", true))
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.EXPLICIT_RULE);
    }

    @Test
    void strictAutoReviewRunsBeforeStoredPrefixAllow() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline(List.of(
            rule(PermissionBehavior.ALLOW, "bash", "prefix:git status", "allow status")
        ));

        PermissionDecision decision = pipeline.decide(
            request("bash", Map.of("command", "git status --short")),
            context(PermissionMode.BYPASS, Map.of("strictAutoReview", true))
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ASK);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.SANDBOX_POLICY);
        assertThat(decision.message()).contains("strictAutoReview");
    }

    @Test
    void permissionRuntimeStateMetadataSupersedesLegacyPermissionModeMetadata() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();

        PermissionDecision decision = pipeline.decide(
            request("bash", Map.of("command", "git push origin feature/security")),
            context(
                PermissionMode.DEFAULT_EXECUTE,
                Map.of("permissionRuntimeState", PermissionRuntimeState.fromLegacy(PermissionMode.BYPASS))
            )
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.MODE_DEFAULT);
    }

    @Test
    void permissionRuntimeStateBehaviorSupersedesLegacyModeForBashRiskDecision() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();
        PermissionRuntimeState bypassBehaviorWithDefaultLegacyMode = runtimeStateWithLegacyMode(
            PermissionRuntimeState.fromLegacy(PermissionMode.BYPASS),
            PermissionMode.DEFAULT_EXECUTE
        );

        PermissionDecision decision = pipeline.decide(
            request("bash", Map.of("command", "git push origin feature/security")),
            context(
                PermissionMode.DEFAULT_EXECUTE,
                Map.of("permissionRuntimeState", bypassBehaviorWithDefaultLegacyMode)
            )
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.MODE_DEFAULT);
    }

    @Test
    void additionalFilesystemPermissionsAllowApprovedWriteOutsideWorkspace() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();

        PermissionDecision decision = pipeline.decide(
            request("write", Map.of("path", "/approved/outside.txt")),
            context(PermissionMode.DEFAULT_EXECUTE, Map.of(
                "additionalPermissions",
                additionalFileSystem("/approved", FileSystemAccessMode.WRITE),
                "approvedAdditionalPermissions",
                true
            ))
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.MODE_DEFAULT);
    }

    @Test
    void unapprovedAdditionalFilesystemPermissionsDoNotAllowWriteOutsideWorkspace() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();

        PermissionDecision decision = pipeline.decide(
            request("write", Map.of("path", "/approved/outside.txt")),
            context(PermissionMode.DEFAULT_EXECUTE, Map.of(
                "additionalPermissions",
                additionalFileSystem("/approved", FileSystemAccessMode.WRITE)
            ))
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.PATH_SAFETY);
    }

    @Test
    void additionalFilesystemPermissionsDoNotConsumeWidePoliciesInPipeline() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();

        PermissionDecision unrestricted = pipeline.decide(
            request("write", Map.of("path", "/approved/outside.txt")),
            context(PermissionMode.DEFAULT_EXECUTE, Map.of(
                "additionalPermissions",
                new AdditionalPermissionProfile(Optional.of(FileSystemPermissionPolicy.unrestricted()), Optional.empty()),
                "approvedAdditionalPermissions",
                true
            ))
        );
        PermissionDecision specialRoot = pipeline.decide(
            request("write", Map.of("path", "/approved/outside.txt")),
            context(PermissionMode.DEFAULT_EXECUTE, Map.of(
                "additionalPermissions",
                additionalRootFileSystem(FileSystemAccessMode.WRITE),
                "approvedAdditionalPermissions",
                true
            ))
        );

        assertThat(unrestricted.behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(unrestricted.reason()).isEqualTo(PermissionDecisionReason.PATH_SAFETY);
        assertThat(specialRoot.behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(specialRoot.reason()).isEqualTo(PermissionDecisionReason.PATH_SAFETY);
    }

    @Test
    void additionalFilesystemPermissionsAllowApprovedBashRedirectOutsideWorkspace() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();

        PermissionDecision decision = pipeline.decide(
            request("bash", Map.of("command", "echo ok > /approved/output.txt")),
            context(PermissionMode.BYPASS, Map.of(
                "additionalPermissions",
                additionalFileSystem("/approved", FileSystemAccessMode.WRITE),
                "approvedAdditionalPermissions",
                true
            ))
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.MODE_DEFAULT);
    }

    @Test
    void additionalFilesystemPermissionsDoNotBypassHardSafety() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();

        PermissionDecision decision = pipeline.decide(
            request("edit", Map.of("path", ".git/config")),
            context(PermissionMode.BYPASS, Map.of(
                "additionalPermissions",
                additionalFileSystem(".git", FileSystemAccessMode.WRITE),
                "approvedAdditionalPermissions",
                true
            ))
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.HARD_SAFETY);
    }

    @Test
    void additionalFilesystemPermissionsDoNotBypassBashRedirectHardSafety(@TempDir Path tempDir) throws IOException {
        Path workspace = tempDir.resolve("workspace");
        Path approved = tempDir.resolve("approved");
        Files.createDirectories(workspace.resolve(".git"));
        Files.createDirectories(approved);
        Files.createSymbolicLink(approved.resolve("git-link"), workspace.resolve(".git"));
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();

        PermissionDecision decision = pipeline.decide(
            request("bash", Map.of("command", "echo ok > " + approved.resolve("git-link/config"))),
            context(PermissionMode.BYPASS, workspace, Map.of(
                "additionalPermissions",
                additionalRootFileSystem(FileSystemAccessMode.WRITE),
                "approvedAdditionalPermissions",
                true
            ))
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.HARD_SAFETY);
    }

    private ToolUseRequest request(String toolName, Map<String, Object> input) {
        return new ToolUseRequest("toolu_1", toolName, input, "msg_1");
    }

    private ToolUseContext context(PermissionMode mode) {
        return context(mode, Map.of());
    }

    private ToolUseContext context(PermissionMode mode, Map<String, Object> extraMetadata) {
        return context(mode, Path.of("/workspace"), extraMetadata);
    }

    private ToolUseContext context(PermissionMode mode, Path cwd, Map<String, Object> extraMetadata) {
        java.util.LinkedHashMap<String, Object> metadata = new java.util.LinkedHashMap<>(extraMetadata);
        metadata.put("permissionMode", mode);
        return new ToolUseContext("ses_1", "msg_1", cwd, metadata);
    }

    private PermissionRule rule(PermissionBehavior behavior, String toolName, String pattern, String reason) {
        return new PermissionRule(
            PermissionRuleSource.SESSION,
            behavior,
            new PermissionRuleValue(toolName, pattern),
            reason
        );
    }

    private AdditionalPermissionProfile additionalFileSystem(String path, FileSystemAccessMode accessMode) {
        return new AdditionalPermissionProfile(
            Optional.of(FileSystemPermissionPolicy.restricted(List.of(
                new FileSystemPermissionEntry(FileSystemPath.exactPath(path), accessMode)
            ))),
            Optional.empty()
        );
    }

    private AdditionalPermissionProfile additionalRootFileSystem(FileSystemAccessMode accessMode) {
        return new AdditionalPermissionProfile(
            Optional.of(FileSystemPermissionPolicy.restricted(List.of(
                new FileSystemPermissionEntry(FileSystemPath.special(FileSystemSpecialPath.ROOT), accessMode)
            ))),
            Optional.empty()
        );
    }

    private PermissionRuntimeState runtimeStateWithLegacyMode(PermissionRuntimeState source, PermissionMode legacyMode) {
        return new PermissionRuntimeState(
            source.approvalPolicy(),
            source.activePermissionProfile(),
            source.legacyBehavior(),
            legacyMode
        );
    }
}
