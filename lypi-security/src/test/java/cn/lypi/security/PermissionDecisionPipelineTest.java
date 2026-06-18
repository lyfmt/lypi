package cn.lypi.security;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.security.AgentMode;
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
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

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

    private ToolUseRequest request(String toolName, Map<String, Object> input) {
        return new ToolUseRequest("toolu_1", toolName, input, "msg_1");
    }

    private ToolUseContext context(PermissionMode mode) {
        return context(mode, Map.of());
    }

    private ToolUseContext context(PermissionMode mode, Map<String, Object> extraMetadata) {
        java.util.LinkedHashMap<String, Object> metadata = new java.util.LinkedHashMap<>(extraMetadata);
        metadata.put("permissionMode", mode);
        return new ToolUseContext("ses_1", "msg_1", Path.of("/workspace"), metadata);
    }

    private PermissionRule rule(PermissionBehavior behavior, String toolName, String pattern, String reason) {
        return new PermissionRule(
            PermissionRuleSource.SESSION,
            behavior,
            new PermissionRuleValue(toolName, pattern),
            reason
        );
    }
}
