package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OverlayCoordinatorTest {
    @Test
    void permissionPreemptsOtherLocalOverlays() {
        OverlayCoordinator coordinator = new OverlayCoordinator();

        coordinator.openSlash();
        coordinator.openFileMention();
        coordinator.openDiff();
        coordinator.openPermission();

        assertEquals(OverlayKind.PERMISSION, coordinator.active());
    }
}
