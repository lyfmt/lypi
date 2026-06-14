package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionOption;
import cn.lypi.contracts.security.PermissionOptionKind;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.security.PermissionRuleSource;
import cn.lypi.contracts.security.PermissionRuleValue;
import cn.lypi.contracts.security.PermissionUpdate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PermissionOverlayTest {
    @Test
    void keepsSelectionVisibleAndSubmitsSelectedOption() {
        PermissionOverlay overlay = new PermissionOverlay(options(), 3);

        overlay.moveDown();
        overlay.moveDown();

        assertEquals("deny", overlay.selectedOptionId());
        assertTrue(overlay.visibleOptionIds().contains("deny"));
        assertEquals("deny", overlay.submit().orElseThrow());
    }

    @Test
    void escapeAndCtrlCCancelUsesEmptyCancelWhenNoCancelOptionIsRendered() {
        PermissionOverlay overlay = new PermissionOverlay(options(), 3);

        assertTrue(overlay.cancel().isEmpty());
    }

    @Test
    void formatsRuleFromStringPermissionRuleOrSuggestedUpdate() {
        PermissionRule rule = rule("bash", "npm test");
        PermissionDecision stringDecision = decision(Optional.empty(), Map.of("rule", "bash:npm test"));
        PermissionDecision ruleDecision = decision(Optional.empty(), Map.of("rule", rule));
        PermissionDecision suggestedDecision = decision(Optional.of(new PermissionUpdate(PermissionRuleSource.SESSION, rule)), Map.of());

        assertEquals("bash:npm test", PermissionOverlay.formatRule(stringDecision));
        assertEquals("bash:npm test", PermissionOverlay.formatRule(ruleDecision));
        assertEquals("bash:npm test", PermissionOverlay.formatRule(suggestedDecision));
    }

    private List<PermissionOption> options() {
        return List.of(
            option("allow_once"),
            option("remember"),
            option("deny")
        );
    }

    private PermissionOption option(String id) {
        return new PermissionOption(id, PermissionOptionKind.ALLOW_ONCE, id, "", Optional.empty(), Map.of());
    }

    private PermissionRule rule(String toolName, String pattern) {
        return new PermissionRule(
            PermissionRuleSource.SESSION,
            PermissionBehavior.ALLOW,
            new PermissionRuleValue(toolName, pattern),
            "test"
        );
    }

    private PermissionDecision decision(Optional<PermissionUpdate> update, Map<String, Object> metadata) {
        return new PermissionDecision(
            PermissionBehavior.ASK,
            PermissionDecisionReason.TOOL_SPECIFIC,
            "need approval",
            update,
            metadata
        );
    }
}
