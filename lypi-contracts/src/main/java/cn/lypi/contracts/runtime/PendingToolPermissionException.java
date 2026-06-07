package cn.lypi.contracts.runtime;

import cn.lypi.contracts.security.PendingPermission;
import java.util.Objects;

/**
 * 表示工具调用因 ASK 权限决策而等待用户确认。
 *
 * NOTE: agent-core 捕获该异常后负责持久化 pending 并返回 WAITING_PERMISSION。
 */
public final class PendingToolPermissionException extends RuntimeException {
    private final PendingPermission pendingPermission;

    public PendingToolPermissionException(PendingPermission pendingPermission) {
        super(pendingPermission == null ? "工具权限请求等待确认。" : pendingPermission.message());
        this.pendingPermission = Objects.requireNonNull(pendingPermission, "pendingPermission must not be null");
    }

    /**
     * 返回等待恢复的权限请求。
     */
    public PendingPermission pendingPermission() {
        return pendingPermission;
    }
}
