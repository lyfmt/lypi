package cn.lypi.tool;

import cn.lypi.contracts.security.PermissionUpdate;
import java.util.Optional;

/**
 * 表示 ASK 权限请求的确认结果。
 *
 * NOTE: 该结果只表达一次请求的交互结论；权限规则持久化必须由事件消费者或后续策略端口处理。
 */
public record PermissionGateResult(
    Status status,
    Optional<PermissionUpdate> permissionUpdate,
    Optional<String> message
) {
    public PermissionGateResult {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        permissionUpdate = permissionUpdate == null ? Optional.empty() : permissionUpdate;
        message = message == null ? Optional.empty() : message.filter(value -> !value.isBlank());
    }

    /**
     * 返回允许继续执行的结果。
     */
    public static PermissionGateResult allow() {
        return allow(Optional.empty());
    }

    /**
     * 返回允许继续执行并携带权限更新建议的结果。
     */
    public static PermissionGateResult allow(Optional<PermissionUpdate> permissionUpdate) {
        return new PermissionGateResult(Status.ALLOW, permissionUpdate, Optional.empty());
    }

    /**
     * 返回拒绝执行的结果。
     */
    public static PermissionGateResult deny(String message) {
        return new PermissionGateResult(Status.DENY, Optional.empty(), Optional.ofNullable(message));
    }

    /**
     * 返回中断工具调用的结果。
     */
    public static PermissionGateResult abort(String message) {
        return new PermissionGateResult(Status.ABORT, Optional.empty(), Optional.ofNullable(message));
    }

    /**
     * 返回等待外部权限响应的结果。
     */
    public static PermissionGateResult pending(String message) {
        return new PermissionGateResult(Status.PENDING, Optional.empty(), Optional.ofNullable(message));
    }

    public enum Status {
        ALLOW,
        DENY,
        ABORT,
        PENDING
    }
}
