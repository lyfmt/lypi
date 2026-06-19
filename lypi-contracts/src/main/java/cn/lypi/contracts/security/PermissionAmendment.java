package cn.lypi.contracts.security;

import java.util.Optional;

/**
 * 表示一次权限修订。
 *
 * 权限修订可以携带 exec policy 更新或 network policy 更新。
 */
public record PermissionAmendment(
    Optional<PermissionUpdate> permissionUpdate,
    Optional<NetworkPolicyAmendment> networkPolicyAmendment
) {
    public PermissionAmendment {
        permissionUpdate = permissionUpdate == null ? Optional.empty() : permissionUpdate;
        networkPolicyAmendment = networkPolicyAmendment == null ? Optional.empty() : networkPolicyAmendment;
        if (permissionUpdate.isEmpty() && networkPolicyAmendment.isEmpty()) {
            throw new IllegalArgumentException("permission amendment requires at least one payload");
        }
    }
}
