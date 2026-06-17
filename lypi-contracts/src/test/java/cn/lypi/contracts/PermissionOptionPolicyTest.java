package cn.lypi.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.event.PermissionDecisionEvent;
import cn.lypi.contracts.event.PermissionRequestEvent;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionOption;
import cn.lypi.contracts.security.PermissionOptionKind;
import cn.lypi.contracts.security.PermissionOptionPolicy;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.security.PermissionRuleSource;
import cn.lypi.contracts.security.PermissionRuleValue;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.security.ApprovalKind;
import cn.lypi.contracts.security.ReviewDecision;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PermissionOptionPolicyTest {
    @Test
    void askOptionsDoNotExposeCancelAsSemanticChoice() {
        PermissionDecision decision = new PermissionDecision(
            PermissionBehavior.ASK,
            PermissionDecisionReason.BASH_RISK,
            "need permission",
            Optional.of(new PermissionUpdate(
                PermissionRuleSource.USER,
                new PermissionRule(
                    PermissionRuleSource.USER,
                    PermissionBehavior.ALLOW,
                    new PermissionRuleValue("bash", "prefix:go test"),
                    "remember prefix"
                )
            )),
            Map.of()
        );

        PermissionOptionPolicy.Options policy = PermissionOptionPolicy.fromDecision(decision);

        assertEquals(List.of("allow_once", "allow_remember", "deny"), policy.options().stream()
            .map(PermissionOption::optionId)
            .toList());
        assertEquals(List.of(
            ReviewDecision.APPROVED,
            ReviewDecision.APPROVED_EXEC_POLICY_AMENDMENT,
            ReviewDecision.DENIED
        ), policy.options().stream()
            .map(option -> option.metadata().get("reviewDecision"))
            .toList());
        assertFalse(policy.options().stream()
            .anyMatch(option -> option.kind() == PermissionOptionKind.CANCEL));
        assertEquals("deny", policy.cancelOptionId());
    }

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

    @Test
    void commandApprovalDefaultsToApprovedOptionalExecPolicyAmendmentAndAbort() {
        PermissionOptionPolicy.Options policy = PermissionOptionPolicy.forApproval(
            ApprovalKind.COMMAND,
            Optional.of(permissionUpdate(PermissionRuleSource.USER)),
            false
        );

        assertEquals(List.of(
            ReviewDecision.APPROVED,
            ReviewDecision.APPROVED_EXEC_POLICY_AMENDMENT,
            ReviewDecision.ABORT
        ), policy.reviewDecisions());
        assertEquals("approved", policy.defaultOptionId());
        assertEquals("abort", policy.cancelOptionId());
    }

    @Test
    void commandApprovalCanOfferSessionApprovalCache() {
        PermissionOptionPolicy.Options policy = PermissionOptionPolicy.forApproval(
            ApprovalKind.COMMAND,
            Optional.empty(),
            true
        );

        assertEquals(List.of(
            ReviewDecision.APPROVED,
            ReviewDecision.APPROVED_FOR_SESSION,
            ReviewDecision.ABORT
        ), policy.reviewDecisions());
    }

    @Test
    void commandApprovalCanOfferExecPolicyAmendmentAndSessionApprovalTogether() {
        PermissionOptionPolicy.Options policy = PermissionOptionPolicy.forApproval(
            ApprovalKind.COMMAND,
            Optional.of(permissionUpdate(PermissionRuleSource.USER)),
            true
        );

        assertEquals(List.of(
            ReviewDecision.APPROVED,
            ReviewDecision.APPROVED_EXEC_POLICY_AMENDMENT,
            ReviewDecision.APPROVED_FOR_SESSION,
            ReviewDecision.ABORT
        ), policy.reviewDecisions());
    }

    @Test
    void additionalPermissionsApprovalOnlyOffersApprovedAndAbort() {
        PermissionOptionPolicy.Options policy = PermissionOptionPolicy.forAdditionalPermissionsApproval();

        assertEquals(List.of(ReviewDecision.APPROVED, ReviewDecision.ABORT), policy.reviewDecisions());
    }

    @Test
    void requestPermissionsApprovalSupportsDeniedAndSessionApproval() {
        PermissionOptionPolicy.Options policy = PermissionOptionPolicy.forApproval(
            ApprovalKind.REQUEST_PERMISSIONS,
            Optional.empty(),
            true
        );

        assertEquals(List.of(
            ReviewDecision.APPROVED,
            ReviewDecision.APPROVED_FOR_SESSION,
            ReviewDecision.DENIED,
            ReviewDecision.ABORT
        ), policy.reviewDecisions());
    }

    @Test
    void networkApprovalCanOfferNetworkPolicyAmendment() {
        PermissionOptionPolicy.Options policy = PermissionOptionPolicy.forApproval(
            ApprovalKind.NETWORK,
            Optional.empty(),
            true
        );

        assertEquals(List.of(
            ReviewDecision.APPROVED,
            ReviewDecision.APPROVED_FOR_SESSION,
            ReviewDecision.NETWORK_POLICY_AMENDMENT,
            ReviewDecision.ABORT
        ), policy.reviewDecisions());
    }

    @Test
    void decisionEventInfersCustomRememberOptionFromAppliedUpdate() {
        PermissionUpdate update = permissionUpdate(PermissionRuleSource.SESSION);
        PermissionDecisionEvent event = new PermissionDecisionEvent(
            "ses_1",
            "perm_1",
            "toolu_1",
            "bash",
            "remember",
            new PermissionDecision(
                PermissionBehavior.ALLOW,
                PermissionDecisionReason.TOOL_SPECIFIC,
                "remembered",
                Optional.of(update),
                Map.of()
            ),
            Optional.of(update),
            Map.of("updateStatus", "applied"),
            Instant.parse("2026-06-01T12:00:00Z")
        );

        assertEquals(ReviewDecision.APPROVED_EXEC_POLICY_AMENDMENT, event.reviewDecision());
    }

    @Test
    void decisionEventInfersCustomRememberOptionFromEventAppliedUpdate() {
        PermissionUpdate update = permissionUpdate(PermissionRuleSource.SESSION);
        PermissionDecisionEvent event = new PermissionDecisionEvent(
            "ses_1",
            "perm_1",
            "toolu_1",
            "bash",
            "remember",
            new PermissionDecision(
                PermissionBehavior.ALLOW,
                PermissionDecisionReason.TOOL_SPECIFIC,
                "remembered",
                Optional.empty(),
                Map.of()
            ),
            Optional.of(update),
            Map.of("updateStatus", "applied"),
            Instant.parse("2026-06-01T12:00:00Z")
        );

        assertEquals(ReviewDecision.APPROVED_EXEC_POLICY_AMENDMENT, event.reviewDecision());
    }

    @Test
    void requestEventRejectsAvailableDecisionsMissingFromOptions() {
        assertThrows(IllegalArgumentException.class, () -> new PermissionRequestEvent(
            "ses_1",
            "perm_1",
            "toolu_1",
            "bash",
            "needs approval",
            "bash {}",
            "needs approval",
            new PermissionDecision(
                PermissionBehavior.ASK,
                PermissionDecisionReason.TOOL_SPECIFIC,
                "needs approval",
                Optional.empty(),
                Map.of()
            ),
            ApprovalKind.COMMAND,
            List.of(ReviewDecision.APPROVED, ReviewDecision.NETWORK_POLICY_AMENDMENT),
            Optional.empty(),
            false,
            PermissionOptionPolicy.forApproval(ApprovalKind.COMMAND, Optional.empty(), false).options(),
            "approved",
            "abort",
            Map.of(),
            Instant.parse("2026-06-01T12:00:00Z")
        ));
    }

    @Test
    void optionsRejectEmptyAndDanglingDefaultIds() {
        assertThrows(IllegalArgumentException.class, () ->
            new PermissionOptionPolicy.Options(List.of(), "approved", "abort"));
        assertThrows(IllegalArgumentException.class, () ->
            new PermissionOptionPolicy.Options(
                PermissionOptionPolicy.forAdditionalPermissionsApproval().options(),
                "missing",
                "abort"
            ));
    }

    private PermissionUpdate permissionUpdate(PermissionRuleSource source) {
        return new PermissionUpdate(
            source,
            new PermissionRule(
                source,
                PermissionBehavior.ALLOW,
                new PermissionRuleValue("bash", "prefix:go test"),
                "remember prefix"
            )
        );
    }
}
