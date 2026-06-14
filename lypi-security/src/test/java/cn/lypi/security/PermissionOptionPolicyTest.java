package cn.lypi.security;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionOptionKind;
import cn.lypi.contracts.security.PermissionOptionPolicy;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.security.PermissionRuleSource;
import cn.lypi.contracts.security.PermissionRuleValue;
import cn.lypi.contracts.security.PermissionUpdate;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PermissionOptionPolicyTest {
    @Test
    void lowRiskAskDefaultsToAllowOnce() {
        PermissionOptionPolicy.Options options = PermissionOptionPolicy.fromDecision(decision(Map.of("riskLevel", "LOW")));

        assertThat(options.defaultOptionId()).isEqualTo("allow_once");
        assertThat(options.cancelOptionId()).isEqualTo("deny");
        assertThat(options.options()).extracting("kind")
            .containsExactly(PermissionOptionKind.ALLOW_ONCE, PermissionOptionKind.DENY);
    }

    @Test
    void highRiskAskDefaultsToDeny() {
        PermissionOptionPolicy.Options options = PermissionOptionPolicy.fromDecision(decision(Map.of("riskLevel", "DESTRUCTIVE")));

        assertThat(options.defaultOptionId()).isEqualTo("deny");
        assertThat(options.cancelOptionId()).isEqualTo("deny");
    }

    @Test
    void suggestedUpdateAddsRememberOption() {
        PermissionUpdate update = update(PermissionRuleSource.SESSION);
        PermissionDecision decision = new PermissionDecision(
            PermissionBehavior.ASK,
            PermissionDecisionReason.TOOL_SPECIFIC,
            "ask",
            Optional.of(update),
            Map.of("riskLevel", "LOW")
        );

        PermissionOptionPolicy.Options options = PermissionOptionPolicy.fromDecision(decision);

        assertThat(options.options()).anySatisfy(option -> {
            assertThat(option.kind()).isEqualTo(PermissionOptionKind.ALLOW_AND_REMEMBER);
            assertThat(option.permissionUpdate()).contains(update);
        });
    }

    @Test
    void rememberOptionOnlyAcceptsUserProjectOrSessionTargets() {
        assertThat(PermissionOptionPolicy.isAllowedRememberTarget(PermissionRuleSource.USER)).isTrue();
        assertThat(PermissionOptionPolicy.isAllowedRememberTarget(PermissionRuleSource.PROJECT)).isTrue();
        assertThat(PermissionOptionPolicy.isAllowedRememberTarget(PermissionRuleSource.SESSION)).isTrue();
        assertThat(PermissionOptionPolicy.isAllowedRememberTarget(PermissionRuleSource.PLATFORM)).isFalse();
        assertThat(PermissionOptionPolicy.isAllowedRememberTarget(PermissionRuleSource.CLI_OVERRIDE)).isFalse();
    }

    private PermissionDecision decision(Map<String, Object> metadata) {
        return new PermissionDecision(
            PermissionBehavior.ASK,
            PermissionDecisionReason.BASH_RISK,
            "ask",
            Optional.empty(),
            metadata
        );
    }

    private PermissionUpdate update(PermissionRuleSource source) {
        return new PermissionUpdate(
            source,
            new PermissionRule(
                source,
                PermissionBehavior.ALLOW,
                new PermissionRuleValue("bash", "git status *"),
                "allow status"
            )
        );
    }
}
