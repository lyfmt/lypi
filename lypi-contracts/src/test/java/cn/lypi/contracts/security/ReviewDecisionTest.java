package cn.lypi.contracts.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ReviewDecisionTest {
    @Test
    void exposesCodexReviewDecisionSet() {
        assertEquals(
            java.util.List.of(
                ReviewDecision.APPROVED,
                ReviewDecision.APPROVED_EXEC_POLICY_AMENDMENT,
                ReviewDecision.APPROVED_FOR_SESSION,
                ReviewDecision.NETWORK_POLICY_AMENDMENT,
                ReviewDecision.DENIED,
                ReviewDecision.TIMED_OUT,
                ReviewDecision.ABORT
            ),
            java.util.List.of(ReviewDecision.values())
        );
    }

    @Test
    void doesNotAddAdditionalPermissionsSpecificApprovalDecision() {
        assertFalse(Arrays.stream(ReviewDecision.values())
            .map(Enum::name)
            .anyMatch(name -> name.contains("ADDITIONAL")));
    }

    @Test
    void legacyPermissionOptionKindsMapToCanonicalReviewDecisions() {
        assertEquals(ReviewDecision.APPROVED, PermissionOptionKind.ALLOW_ONCE.reviewDecision());
        assertEquals(ReviewDecision.APPROVED_EXEC_POLICY_AMENDMENT, PermissionOptionKind.ALLOW_AND_REMEMBER.reviewDecision());
        assertEquals(ReviewDecision.DENIED, PermissionOptionKind.DENY.reviewDecision());
        assertEquals(ReviewDecision.ABORT, PermissionOptionKind.CANCEL.reviewDecision());
        assertTrue(PermissionOptionKind.ALLOW_AND_REMEMBER.supportsSessionApproval());
    }
}
