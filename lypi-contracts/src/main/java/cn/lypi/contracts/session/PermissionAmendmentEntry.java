package cn.lypi.contracts.session;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import cn.lypi.contracts.security.NetworkPolicyAmendment;
import cn.lypi.contracts.security.PermissionAmendment;
import cn.lypi.contracts.security.PermissionUpdate;
import java.time.Instant;
import java.util.Optional;

/**
 * 记录权限修订持久化结果。
 */
public record PermissionAmendmentEntry(
    String id,
    String parentId,
    @JsonIgnore
    PermissionAmendment permissionAmendment,
    Instant timestamp
) implements SessionEntry {
    public PermissionAmendmentEntry {
        if (permissionAmendment == null) {
            throw new IllegalArgumentException("permission amendment requires at least one payload");
        }
    }

    public PermissionAmendmentEntry(
        String id,
        String parentId,
        Optional<PermissionUpdate> permissionUpdate,
        Optional<NetworkPolicyAmendment> networkPolicyAmendment,
        Instant timestamp
    ) {
        this(id, parentId, new PermissionAmendment(permissionUpdate, networkPolicyAmendment), timestamp);
    }

    /**
     * 返回兼容旧 JSON 字段的 exec policy 修订。
     */
    @JsonGetter("permissionUpdate")
    public Optional<PermissionUpdate> permissionUpdate() {
        return permissionAmendment.permissionUpdate();
    }

    /**
     * 返回兼容旧 JSON 字段的 network policy 修订。
     */
    @JsonGetter("networkPolicyAmendment")
    public Optional<NetworkPolicyAmendment> networkPolicyAmendment() {
        return permissionAmendment.networkPolicyAmendment();
    }

    @JsonCreator
    public static PermissionAmendmentEntry create(
        @JsonProperty("id") String id,
        @JsonProperty("parentId") String parentId,
        @JsonProperty("permissionAmendment") PermissionAmendment permissionAmendment,
        @JsonProperty("permissionUpdate") Optional<PermissionUpdate> permissionUpdate,
        @JsonProperty("networkPolicyAmendment") Optional<NetworkPolicyAmendment> networkPolicyAmendment,
        @JsonProperty("timestamp") Instant timestamp
    ) {
        return new PermissionAmendmentEntry(
            id,
            parentId,
            permissionAmendment == null ? new PermissionAmendment(permissionUpdate, networkPolicyAmendment) : permissionAmendment,
            timestamp
        );
    }
}
