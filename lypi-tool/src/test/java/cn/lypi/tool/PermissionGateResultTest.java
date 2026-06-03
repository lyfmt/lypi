package cn.lypi.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class PermissionGateResultTest {
    @Test
    void createsAllowDenyAndAbortResults() {
        PermissionGateResult allow = PermissionGateResult.allow();
        PermissionGateResult deny = PermissionGateResult.deny("user denied");
        PermissionGateResult abort = PermissionGateResult.abort("interrupted");

        assertEquals(PermissionGateResult.Status.ALLOW, allow.status());
        assertFalse(allow.message().isPresent());
        assertEquals(PermissionGateResult.Status.DENY, deny.status());
        assertEquals(Optional.of("user denied"), deny.message());
        assertEquals(PermissionGateResult.Status.ABORT, abort.status());
        assertEquals(Optional.of("interrupted"), abort.message());
        assertTrue(allow.permissionUpdate().isEmpty());
    }
}
