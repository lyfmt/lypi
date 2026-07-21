package cn.lypi.runtime.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import org.junit.jupiter.api.Test;

class PermissionRuntimeSubagentEndToEndTest {
    @Test
    void autoChildPermissionKeepsWorkspaceProfileFromAskParent() {
        assertChildProjection(PermissionRuntimeState.forMode(PermissionMode.ASK));
    }

    @Test
    void autoChildPermissionKeepsDangerProfileFromBypassParent() {
        assertChildProjection(PermissionRuntimeState.forMode(PermissionMode.BYPASS));
    }

    private void assertChildProjection(PermissionRuntimeState parent) {
        PermissionRuntimeState auto = PermissionRuntimeState.forMode(PermissionMode.AUTO);
        PermissionRuntimeState child = new PermissionRuntimeState(
            auto.approvalPolicy(),
            parent.activePermissionProfile(),
            parent.permissionProfile(),
            auto.legacyBehavior(),
            PermissionMode.AUTO
        );

        assertThat(child.mode()).isEqualTo(PermissionMode.AUTO);
        assertThat(child.approvalPolicy()).isEqualTo(auto.approvalPolicy());
        assertThat(child.activePermissionProfile()).isEqualTo(parent.activePermissionProfile());
        assertThat(child.permissionProfile()).isEqualTo(parent.permissionProfile());
        assertThat(child.legacyBehavior()).isEqualTo(auto.legacyBehavior());
    }
}
