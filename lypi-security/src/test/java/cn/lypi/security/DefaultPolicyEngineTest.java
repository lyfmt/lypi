package cn.lypi.security;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.security.PermissionRuleSource;
import cn.lypi.contracts.security.PermissionRuleValue;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultPolicyEngineTest {
    @Test
    void decideAppliesExplicitDenyBeforeAllowRules() {
        DefaultPolicyEngine engine = new DefaultPolicyEngine(List.of(
            rule(PermissionBehavior.ALLOW, "bash", "rm *", "too broad"),
            rule(PermissionBehavior.DENY, "bash", "rm -rf *", "protect filesystem")
        ));

        PermissionDecision decision = engine.decide(
            request("bash", Map.of("command", "rm -rf target")),
            context(PermissionMode.BYPASS)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.EXPLICIT_RULE);
        assertThat(decision.message()).contains("protect filesystem");
    }

    @Test
    void decideDoesNotLetAllowRuleBypassUnknownBashRisk() {
        DefaultPolicyEngine engine = new DefaultPolicyEngine(List.of(
            rule(PermissionBehavior.ALLOW, "bash", "*", "allow bash")
        ));

        PermissionDecision decision = engine.decide(
            request("bash", Map.of("command", "bash -c \"$(cat script.sh)\"")),
            context(PermissionMode.BYPASS)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ASK);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.BASH_RISK);
    }

    @Test
    void decideRejectsPathsThatEscapeWorkingDirectory() {
        DefaultPolicyEngine engine = new DefaultPolicyEngine(List.of(
            rule(PermissionBehavior.ALLOW, "read_file", "*", "allow reads")
        ));

        PermissionDecision decision = engine.decide(
            request("read_file", Map.of("path", "../secret.txt")),
            context(PermissionMode.BYPASS)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.PATH_SAFETY);
    }

    @Test
    void decideDeniesWriteToolsInPlanMode() {
        DefaultPolicyEngine engine = new DefaultPolicyEngine();

        PermissionDecision decision = engine.decide(
            request("apply_patch", Map.of("patch", "*** Begin Patch")),
            context(PermissionMode.PLAN)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.MODE_DEFAULT);
        assertThat(decision.message()).contains("Plan Mode");
    }

    @Test
    void decideDoesNotLetAllowRuleBypassPlanModeWriteDeny() {
        DefaultPolicyEngine engine = new DefaultPolicyEngine(List.of(
            rule(PermissionBehavior.ALLOW, "apply_patch", "*", "allow edits")
        ));

        PermissionDecision decision = engine.decide(
            request("apply_patch", Map.of("patch", "*** Begin Patch")),
            context(PermissionMode.PLAN)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.MODE_DEFAULT);
    }

    @Test
    void decideAsksForUnknownBashEvenWhenModeWouldAllow() {
        DefaultPolicyEngine engine = new DefaultPolicyEngine();

        PermissionDecision decision = engine.decide(
            request("bash", Map.of("command", "bash -c \"$(cat script.sh)\"")),
            context(PermissionMode.DONT_ASK)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ASK);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.BASH_RISK);
        assertThat(decision.metadata()).containsKey("bashRisk");
    }

    @Test
    void decideAsksForBashPipelineRiskInDefaultExecuteMode() {
        DefaultPolicyEngine engine = new DefaultPolicyEngine();

        PermissionDecision decision = engine.decide(
            request("bash", Map.of("command", "cat script.sh | sh")),
            context(PermissionMode.DEFAULT_EXECUTE)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ASK);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.BASH_RISK);
    }

    @Test
    void decideAsksForBashRedirectTargetsOutsideCwdUnlessBypass() {
        DefaultPolicyEngine engine = new DefaultPolicyEngine();

        PermissionDecision defaultExecuteDecision = engine.decide(
            request("bash", Map.of("command", "echo ok > ../secret.txt")),
            context(PermissionMode.DEFAULT_EXECUTE)
        );
        PermissionDecision dontAskDecision = engine.decide(
            request("bash", Map.of("command", "echo ok > ../secret.txt")),
            context(PermissionMode.DONT_ASK)
        );
        PermissionDecision acceptEditsDecision = engine.decide(
            request("bash", Map.of("command", "echo ok > ../secret.txt")),
            context(PermissionMode.ACCEPT_EDITS)
        );
        PermissionDecision bypassDecision = engine.decide(
            request("bash", Map.of("command", "echo ok > ../secret.txt")),
            context(PermissionMode.BYPASS)
        );

        assertThat(defaultExecuteDecision.behavior()).isEqualTo(PermissionBehavior.ASK);
        assertThat(defaultExecuteDecision.reason()).isEqualTo(PermissionDecisionReason.PATH_SAFETY);
        assertThat(dontAskDecision.behavior()).isEqualTo(PermissionBehavior.ASK);
        assertThat(dontAskDecision.reason()).isEqualTo(PermissionDecisionReason.PATH_SAFETY);
        assertThat(acceptEditsDecision.behavior()).isEqualTo(PermissionBehavior.ASK);
        assertThat(acceptEditsDecision.reason()).isEqualTo(PermissionDecisionReason.PATH_SAFETY);
        assertThat(bypassDecision.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(bypassDecision.reason()).isEqualTo(PermissionDecisionReason.MODE_DEFAULT);
    }

    @Test
    void decideAllowsReadOnlyToolsByDefaultExecuteMode() {
        DefaultPolicyEngine engine = new DefaultPolicyEngine();

        PermissionDecision decision = engine.decide(
            request("read_file", Map.of("path", "README.md")),
            context(PermissionMode.DEFAULT_EXECUTE)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.MODE_DEFAULT);
    }

    @Test
    void decideMergesSessionRulesFromContextMetadata() {
        DefaultPolicyEngine engine = new DefaultPolicyEngine();
        PermissionRule sessionRule = rule(PermissionBehavior.ASK, "bash", "git push *", "confirm remote updates");

        PermissionDecision decision = engine.decide(
            request("bash", Map.of("command", "git push origin feature/security-module")),
            context(PermissionMode.BYPASS, Map.of("permissionRules", List.of(sessionRule)))
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ASK);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.EXPLICIT_RULE);
        assertThat(decision.message()).contains("confirm remote updates");
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
