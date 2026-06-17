package cn.lypi.contracts.session;

import cn.lypi.contracts.security.NetworkPolicyAmendment;
import cn.lypi.contracts.security.PermissionUpdate;
import java.time.Instant;
import java.util.Optional;

/**
 * 记录权限修订持久化结果。
 */
public record PermissionAmendmentEntry(
    String id,
    String parentId,
    Optional<PermissionUpdate> permissionUpdate,
    Optional<NetworkPolicyAmendment> networkPolicyAmendment,
    Instant timestamp
) implements SessionEntry {
    public PermissionAmendmentEntry {
        permissionUpdate = permissionUpdate == null ? Optional.empty() : permissionUpdate;
        networkPolicyAmendment = networkPolicyAmendment == null ? Optional.empty() : networkPolicyAmendment;
        if (permissionUpdate.isEmpty() && networkPolicyAmendment.isEmpty()) {
            throw new IllegalArgumentException("permission amendment requires at least one payload");
        }
    }
}
