package cn.lypi.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionOption;
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
    void normalizesExplicitDefaultToExistingOptionWhenRememberOptionIsFiltered() {
        PermissionDecision decision = new PermissionDecision(
            PermissionBehavior.ASK,
            PermissionDecisionReason.TOOL_SPECIFIC,
            "need permission",
            Optional.of(new PermissionUpdate(
                PermissionRuleSource.PLATFORM,
                new PermissionRule(
                    PermissionRuleSource.PLATFORM,
                    PermissionBehavior.ALLOW,
                    new PermissionRuleValue("bash", "git status"),
                    "platform managed"
                )
            )),
            Map.of("defaultOptionId", "allow_remember")
        );

        PermissionOptionPolicy.Options policy = PermissionOptionPolicy.fromDecision(decision);

        assertEquals("allow_once", policy.defaultOptionId());
        assertTrue(policy.options().stream()
            .map(PermissionOption::optionId)
            .noneMatch("allow_remember"::equals));
        assertTrue(policy.options().stream()
            .map(PermissionOption::optionId)
            .anyMatch(policy.defaultOptionId()::equals));
        assertTrue(policy.options().stream()
            .map(PermissionOption::optionId)
            .anyMatch(policy.cancelOptionId()::equals));
    }
}
