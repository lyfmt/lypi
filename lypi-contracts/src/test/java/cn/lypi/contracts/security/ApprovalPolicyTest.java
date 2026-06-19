package cn.lypi.contracts.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ApprovalPolicyTest {
    @Test
    void mapsLegacyDefaultExecuteToOnRequestApprovalPolicy() {
        ApprovalPolicy policy = ApprovalPolicy.fromLegacy(PermissionMode.DEFAULT_EXECUTE);

        assertEquals(ApprovalMode.ON_REQUEST, policy.mode());
    }

    @Test
    void mapsLegacyAcceptEditsToOnRequestApprovalPolicy() {
        ApprovalPolicy policy = ApprovalPolicy.fromLegacy(PermissionMode.ACCEPT_EDITS);

        assertEquals(ApprovalMode.ON_REQUEST, policy.mode());
    }

    @Test
    void mapsLegacyBypassToNeverApprovalPolicy() {
        ApprovalPolicy policy = ApprovalPolicy.fromLegacy(PermissionMode.BYPASS);

        assertEquals(ApprovalMode.NEVER, policy.mode());
    }

    @Test
    void rejectsGranularModeWithoutGranularPolicy() {
        assertThrows(IllegalArgumentException.class, () -> new ApprovalPolicy(ApprovalMode.GRANULAR));
    }

    @Test
    void rejectsGranularPolicyOnNonGranularMode() {
        GranularApprovalPolicy granularPolicy = granularPolicy();

        assertThrows(IllegalArgumentException.class, () ->
            new ApprovalPolicy(ApprovalMode.ON_REQUEST, java.util.Optional.of(granularPolicy)));
    }

    @Test
    void acceptsGranularModeWithGranularPolicy() {
        GranularApprovalPolicy granularPolicy = granularPolicy();

        ApprovalPolicy policy = new ApprovalPolicy(ApprovalMode.GRANULAR, java.util.Optional.of(granularPolicy));

        assertEquals(ApprovalMode.GRANULAR, policy.mode());
        assertEquals(java.util.Optional.of(granularPolicy), policy.granularApprovalPolicy());
    }

    @Test
    void rejectsNullGranularApprovalPolicyComponents() {
        assertThrows(NullPointerException.class, () ->
            new GranularApprovalPolicy(
                null,
                ApprovalMode.ON_REQUEST,
                ApprovalMode.ON_REQUEST,
                ApprovalMode.ON_REQUEST,
                ApprovalMode.ON_REQUEST
            ));
    }

    private GranularApprovalPolicy granularPolicy() {
        return new GranularApprovalPolicy(
            ApprovalMode.ON_REQUEST,
            ApprovalMode.ON_REQUEST,
            ApprovalMode.ON_REQUEST,
            ApprovalMode.ON_REQUEST,
            ApprovalMode.ON_REQUEST
        );
    }
}
