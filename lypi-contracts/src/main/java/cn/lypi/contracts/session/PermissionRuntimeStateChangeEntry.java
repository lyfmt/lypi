package cn.lypi.contracts.session;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import java.time.Instant;
import java.util.Objects;

/**
 * 记录会话权限运行态的切换。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PermissionRuntimeStateChangeEntry(
    String id,
    String parentId,
    PermissionRuntimeState permissionRuntimeState,
    Instant timestamp
) implements SessionEntry {
    public PermissionRuntimeStateChangeEntry {
        permissionRuntimeState = Objects.requireNonNull(permissionRuntimeState, "permissionRuntimeState");
    }

    public PermissionRuntimeStateChangeEntry(
        String id,
        String parentId,
        PermissionMode permissionMode,
        Instant timestamp
    ) {
        this(id, parentId, PermissionRuntimeState.fromLegacy(permissionMode), timestamp);
    }

    /**
     * 返回兼容旧协议的权限模式。
     *
     * NOTE: 新代码应读取 permissionRuntimeState。
     */
    @JsonGetter("permissionMode")
    public PermissionMode permissionMode() {
        return permissionRuntimeState.legacyPermissionMode();
    }

    @JsonCreator
    public static PermissionRuntimeStateChangeEntry create(
        @JsonProperty("id") String id,
        @JsonProperty("parentId") String parentId,
        @JsonProperty("permissionRuntimeState") PermissionRuntimeState permissionRuntimeState,
        @JsonProperty("permissionMode") PermissionMode permissionMode,
        @JsonProperty("timestamp") Instant timestamp
    ) {
        return new PermissionRuntimeStateChangeEntry(
            id,
            parentId,
            permissionRuntimeState == null
                ? PermissionRuntimeState.fromLegacy(permissionMode == null ? PermissionMode.DEFAULT_EXECUTE : permissionMode)
                : permissionRuntimeState,
            timestamp
        );
    }
}
