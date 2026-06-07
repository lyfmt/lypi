package cn.lypi.contracts.session;

import cn.lypi.contracts.security.PermissionResponse;
import java.time.Instant;
import java.util.Objects;

/**
 * 记录用户对权限请求的最终响应。
 *
 * NOTE: 拒绝响应是否进入模型上下文由 agent-core 通过 tool result message 表达。
 */
public record PermissionDecisionEntry(
    String id,
    String parentId,
    PermissionResponse response,
    Instant timestamp
) implements SessionEntry {
    public PermissionDecisionEntry {
        response = Objects.requireNonNull(response, "response must not be null");
    }
}
