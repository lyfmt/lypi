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
    void decideRejectsWritesThatEscapeWorkingDirectory() {
        DefaultPolicyEngine engine = new DefaultPolicyEngine(List.of(
            rule(PermissionBehavior.ALLOW, "write", "*", "allow writes")
        ));

        PermissionDecision decision = engine.decide(
            request("write", Map.of("path", "../secret.txt")),
            context(PermissionMode.DEFAULT_EXECUTE)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.SANDBOX_POLICY);
    }

    @Test
    void decideAsksForUnknownBashEvenWhenModeWouldAllow() {
        DefaultPolicyEngine engine = new DefaultPolicyEngine();

        PermissionDecision decision = engine.decide(
            request("bash", Map.of("command", "bash -c \"$(cat script.sh)\"")),
            context(PermissionMode.ACCEPT_EDITS)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ASK);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.BASH_RISK);
        assertThat(decision.metadata()).containsKey("bashRisk");
    }

    @Test
    void decideAllowsHighRiskBashInAcceptEditsModeWhenRiskIsStaticallyKnown() {
        DefaultPolicyEngine engine = new DefaultPolicyEngine();

        PermissionDecision decision = engine.decide(
            request("bash", Map.of("command", "git push origin feature/security")),
            context(PermissionMode.ACCEPT_EDITS)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ALLOW);
    }

    @Test
    void decideStillAsksForUnknownBashInBypassMode() {
        DefaultPolicyEngine engine = new DefaultPolicyEngine();

        PermissionDecision decision = engine.decide(
            request("bash", Map.of("command", "bash -c \"$(cat script.sh)\"")),
            context(PermissionMode.BYPASS)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ASK);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.BASH_RISK);
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
    void decideAsksForWorkspaceBashRedirectsInDefaultExecuteMode() {
        DefaultPolicyEngine engine = new DefaultPolicyEngine();

        PermissionDecision decision = engine.decide(
            request("bash", Map.of("command", "echo ok > notes/output.txt")),
            context(PermissionMode.DEFAULT_EXECUTE)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ASK);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.BASH_RISK);
        assertThat(decision.metadata()).containsKey("bashRisk");
        assertThat(decision.suggestedUpdate()).isEmpty();
    }

    @Test
    void decideDeniesBashRedirectsThatEscapeWorkspace() {
        DefaultPolicyEngine engine = new DefaultPolicyEngine();

        PermissionDecision decision = engine.decide(
            request("bash", Map.of("command", "echo ok > ../output.txt")),
            context(PermissionMode.DEFAULT_EXECUTE)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.SANDBOX_POLICY);
        assertThat(decision.message()).contains("profile");
    }

    @Test
    void decideAllowsWorkspaceBashRedirectsInBypassMode() {
        DefaultPolicyEngine engine = new DefaultPolicyEngine();

        PermissionDecision decision = engine.decide(
            request("bash", Map.of("command", "echo ok > notes/output.txt")),
            context(PermissionMode.BYPASS)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.MODE_DEFAULT);
    }

    @Test
    void decideAllowsWorkspaceBashRedirectsWhenStoredPrefixRuleMatches() {
        DefaultPolicyEngine engine = new DefaultPolicyEngine(List.of(
            rule(PermissionBehavior.ALLOW, "bash", "prefix:echo ok", "remember echo")
        ));

        PermissionDecision decision = engine.decide(
            request("bash", Map.of("command", "echo ok > notes/output.txt")),
            context(PermissionMode.DEFAULT_EXECUTE)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.EXPLICIT_RULE);
    }

    @Test
    void decideAllowsWorkspaceBashRedirectsWhenStoredPatternRuleMatches() {
        DefaultPolicyEngine engine = new DefaultPolicyEngine(List.of(
            rule(PermissionBehavior.ALLOW, "bash", "echo ok > notes/output.txt", "remember exact redirect")
        ));

        PermissionDecision decision = engine.decide(
            request("bash", Map.of("command", "echo ok > notes/output.txt")),
            context(PermissionMode.DEFAULT_EXECUTE)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.EXPLICIT_RULE);
    }

    @Test
    void decideChecksBashRedirectTargetsRelativeToRequestedCwd() {
        DefaultPolicyEngine engine = new DefaultPolicyEngine();

        PermissionDecision decision = engine.decide(
            request("bash", Map.of("command", "echo ok > ../output.txt", "cwd", "subdir")),
            context(PermissionMode.DEFAULT_EXECUTE)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ASK);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.BASH_RISK);
    }

    @Test
    void decideAppliesRedirectPolicyToFileDescriptorAndCompactRedirects() {
        DefaultPolicyEngine engine = new DefaultPolicyEngine();

        PermissionDecision stdoutRedirect = engine.decide(
            request("bash", Map.of("command", "echo ok 1> notes/output.txt")),
            context(PermissionMode.DEFAULT_EXECUTE)
        );
        PermissionDecision stderrAppendRedirect = engine.decide(
            request("bash", Map.of("command", "make test 2>> logs/stderr.txt")),
            context(PermissionMode.BYPASS)
        );
        PermissionDecision compactRedirect = engine.decide(
            request("bash", Map.of("command", "echo hi>../output.txt")),
            context(PermissionMode.BYPASS)
        );

        assertThat(stdoutRedirect.behavior()).isEqualTo(PermissionBehavior.ASK);
        assertThat(stdoutRedirect.reason()).isEqualTo(PermissionDecisionReason.BASH_RISK);
        assertThat(stderrAppendRedirect.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(stderrAppendRedirect.reason()).isEqualTo(PermissionDecisionReason.MODE_DEFAULT);
        assertThat(compactRedirect.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(compactRedirect.reason()).isEqualTo(PermissionDecisionReason.MODE_DEFAULT);
    }

    @Test
    void decideAsksForBashProcessSubstitutionRiskInBypassMode() {
        DefaultPolicyEngine engine = new DefaultPolicyEngine();

        PermissionDecision decision = engine.decide(
            request("bash", Map.of("command", "cat <(rm -rf target)")),
            context(PermissionMode.BYPASS)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ASK);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.BASH_RISK);
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

    @Test
    void decideSuggestsRequestedPrefixRuleWhenItCoversAllParsedCommands() {
        DefaultPolicyEngine engine = new DefaultPolicyEngine();

        PermissionDecision decision = engine.decide(
            request("bash", Map.of("command", "go test ./...", "prefix_rule", List.of("go", "test"))),
            context(PermissionMode.DEFAULT_EXECUTE)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ASK);
        assertThat(decision.suggestedUpdate()).isPresent();
        PermissionRule rule = decision.suggestedUpdate().orElseThrow().rule();
        assertThat(rule.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(rule.value().toolName()).isEqualTo("bash");
        assertThat(rule.value().pattern()).isEqualTo("prefix:go test");
    }

    @Test
    void decideSuggestsRequestedPrefixRuleForStaticBashLoginCommand() {
        DefaultPolicyEngine engine = new DefaultPolicyEngine();

        PermissionDecision decision = engine.decide(
            request("bash", Map.of(
                "command", "bash -lc \"mvn -pl lypi-security test\"",
                "prefix_rule", List.of("mvn", "-pl")
            )),
            context(PermissionMode.DEFAULT_EXECUTE)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ASK);
        assertThat(decision.message()).contains("默认执行模式");
        assertThat(decision.suggestedUpdate()).isPresent();
        assertThat(decision.suggestedUpdate().orElseThrow().rule().value().pattern()).isEqualTo("prefix:mvn -pl");
    }

    @Test
    void decideRejectsBannedRequestedPrefixRule() {
        DefaultPolicyEngine engine = new DefaultPolicyEngine();

        PermissionDecision decision = engine.decide(
            request("bash", Map.of("command", "python3 script.py", "prefix_rule", List.of("python3"))),
            context(PermissionMode.DEFAULT_EXECUTE)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ASK);
        assertThat(decision.suggestedUpdate()).isEmpty();
    }

    @Test
    void decideRejectsTooBroadRequestedPrefixRule() {
        DefaultPolicyEngine engine = new DefaultPolicyEngine();

        PermissionDecision decision = engine.decide(
            request("bash", Map.of("command", "go test ./...", "prefix_rule", List.of("go"))),
            context(PermissionMode.DEFAULT_EXECUTE)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ASK);
        assertThat(decision.suggestedUpdate()).isEmpty();
    }

    @Test
    void decideDerivesPrefixRuleWhenNoRequestedPrefixIsValid() {
        DefaultPolicyEngine engine = new DefaultPolicyEngine();

        PermissionDecision decision = engine.decide(
            request("bash", Map.of("command", "cargo build")),
            context(PermissionMode.DEFAULT_EXECUTE)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ASK);
        assertThat(decision.suggestedUpdate()).isPresent();
        assertThat(decision.suggestedUpdate().orElseThrow().rule().value().pattern()).isEqualTo("prefix:cargo build");
    }

    @Test
    void decideDoesNotSuggestRequestedPrefixWhenItDoesNotCoverEverySegment() {
        DefaultPolicyEngine engine = new DefaultPolicyEngine();

        PermissionDecision decision = engine.decide(
            request("bash", Map.of(
                "command", "go test ./... && echo ok",
                "prefix_rule", List.of("go", "test")
            )),
            context(PermissionMode.DEFAULT_EXECUTE)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ASK);
        assertThat(decision.suggestedUpdate()).isEmpty();
    }

    @Test
    void decideAllowsBashWhenStoredPrefixRuleMatches() {
        DefaultPolicyEngine engine = new DefaultPolicyEngine(List.of(
            rule(PermissionBehavior.ALLOW, "bash", "prefix:go test", "remember go test")
        ));

        PermissionDecision decision = engine.decide(
            request("bash", Map.of("command", "go test ./...")),
            context(PermissionMode.DEFAULT_EXECUTE)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.EXPLICIT_RULE);
    }

    @Test
    void decideDoesNotLetStoredPrefixRuleBypassUnknownBashRisk() {
        DefaultPolicyEngine engine = new DefaultPolicyEngine(List.of(
            rule(PermissionBehavior.ALLOW, "bash", "prefix:go test", "remember go test")
        ));

        PermissionDecision decision = engine.decide(
            request("bash", Map.of("command", "go test \"$(cat args)\"")),
            context(PermissionMode.BYPASS)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ASK);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.BASH_RISK);
    }

    @Test
    void decideAppliesDenyRuleToBashSubcommandsBeforeRiskAsk() {
        DefaultPolicyEngine engine = new DefaultPolicyEngine(List.of(
            rule(PermissionBehavior.DENY, "bash", "rm -rf *", "protect delete")
        ));

        PermissionDecision decision = engine.decide(
            request("bash", Map.of("command", "git status && rm -rf target")),
            context(PermissionMode.BYPASS)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.EXPLICIT_RULE);
    }

    @Test
    void decideDoesNotLetAllowRulePermitCompoundBashCommand() {
        DefaultPolicyEngine engine = new DefaultPolicyEngine(List.of(
            rule(PermissionBehavior.ALLOW, "bash", "git status *", "allow status")
        ));

        PermissionDecision decision = engine.decide(
            request("bash", Map.of("command", "git status && unknown-tool")),
            context(PermissionMode.DEFAULT_EXECUTE)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ASK);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.BASH_RISK);
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
