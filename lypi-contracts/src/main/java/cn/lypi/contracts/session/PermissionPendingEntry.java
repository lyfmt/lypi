package cn.lypi.contracts.session;

import cn.lypi.contracts.security.PendingPermission;
import java.time.Instant;
import java.util.Objects;

/**
 * 记录一次等待中的权限请求。
 *
 * NOTE: 该 entry 不进入模型上下文，只用于恢复和审计。
 */
public record PermissionPendingEntry(
    String id,
    String parentId,
    PendingPermission pendingPermission,
    String contextLeafId,
    int currentToolRound,
    int maxToolRounds,
    Instant timestamp
) implements SessionEntry {
    public PermissionPendingEntry(String id, String parentId, PendingPermission pendingPermission, Instant timestamp) {
        this(id, parentId, pendingPermission, parentId, 1, 1, timestamp);
    }

    public PermissionPendingEntry {
        pendingPermission = Objects.requireNonNull(pendingPermission, "pendingPermission must not be null");
        contextLeafId = contextLeafId == null || contextLeafId.isBlank() ? parentId : contextLeafId;
        currentToolRound = Math.max(0, currentToolRound);
        maxToolRounds = Math.max(currentToolRound, maxToolRounds);
    }
}
