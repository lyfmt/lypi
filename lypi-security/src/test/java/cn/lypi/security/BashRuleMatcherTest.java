package cn.lypi.security;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.security.BashRiskAnalysis;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.security.PermissionRuleSource;
import cn.lypi.contracts.security.PermissionRuleValue;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BashRuleMatcherTest {
    private final BashRiskAnalyzer analyzer = new DefaultBashRiskAnalyzer();

    @Test
    void denyRuleMatchesDangerousSubcommandInsideCompoundCommand() {
        BashRuleMatcher matcher = new BashRuleMatcher(new BashCommandNormalizer());
        PermissionRule denyRm = rule(PermissionBehavior.DENY, "bash", "rm -rf *", "protect delete");
        BashRiskAnalysis analysis = analyzer.analyze("git status && rm -rf target");

        boolean matched = matcher.matches(
            denyRm,
            request("bash", Map.of("command", "git status && rm -rf target")),
            analysis
        );

        assertThat(matched).isTrue();
    }

    @Test
    void allowRuleDoesNotMatchCompoundCommandByPrefix() {
        BashRuleMatcher matcher = new BashRuleMatcher(new BashCommandNormalizer());
        PermissionRule allowGit = rule(PermissionBehavior.ALLOW, "bash", "git status *", "allow status");
        BashRiskAnalysis analysis = analyzer.analyze("git status && echo ok");

        boolean matched = matcher.matches(
            allowGit,
            request("bash", Map.of("command", "git status && echo ok")),
            analysis
        );

        assertThat(matched).isFalse();
    }

    private ToolUseRequest request(String toolName, Map<String, Object> input) {
        return new ToolUseRequest("toolu_1", toolName, input, "msg_1");
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
