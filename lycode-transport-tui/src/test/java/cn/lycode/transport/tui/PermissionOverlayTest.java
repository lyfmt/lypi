package cn.lycode.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lycode.contracts.security.PermissionBehavior;
import cn.lycode.contracts.security.PermissionDecision;
import cn.lycode.contracts.security.PermissionDecisionReason;
import cn.lycode.contracts.security.PermissionOption;
import cn.lycode.contracts.security.PermissionOptionKind;
import cn.lycode.contracts.security.PermissionRule;
import cn.lycode.contracts.security.PermissionRuleSource;
import cn.lycode.contracts.security.PermissionRuleValue;
import cn.lycode.contracts.security.PermissionUpdate;
import cn.lycode.contracts.security.ReviewDecision;
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

    @Test
    void formatsCodexReviewDecisionSummary() {
        assertEquals(
            "APPROVED, APPROVED_FOR_SESSION, ABORT",
            PermissionOverlay.formatReviewDecisions(List.of(
                ReviewDecision.APPROVED,
                ReviewDecision.APPROVED_FOR_SESSION,
                ReviewDecision.ABORT
            ))
        );
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
