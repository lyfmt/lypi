package cn.lypi.contracts.security;

import cn.lypi.contracts.tool.ToolUseRequest;
import java.util.Objects;

/**
 * 表示等待用户确认的工具权限请求。
 *
 * NOTE: 该对象是 agent-core 恢复 turn 的检查点，必须携带原始工具调用。
 */
public record PendingPermission(
    String turnId,
    String toolUseId,
    String toolName,
    String renderedToolUse,
    String message,
    PermissionDecision decision,
    ToolUseRequest request
) {
    public PendingPermission {
        turnId = normalizeRequired(turnId, "turnId");
        request = Objects.requireNonNull(request, "request must not be null");
        toolUseId = normalizeBlank(toolUseId, request.toolUseId());
        toolName = normalizeBlank(toolName, request.toolName());
        renderedToolUse = renderedToolUse == null ? "" : renderedToolUse;
        message = message == null || message.isBlank() ? "权限请求需要确认。" : message;
        decision = Objects.requireNonNull(decision, "decision must not be null");
    }

    private static String normalizeRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private static String normalizeBlank(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback == null ? "" : fallback;
        }
        return value;
    }
}
