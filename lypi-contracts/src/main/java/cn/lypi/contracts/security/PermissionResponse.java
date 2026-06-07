package cn.lypi.contracts.security;

import java.util.Optional;

/**
 * 表示用户对一次权限请求的响应。
 *
 * 响应用于恢复等待中的工具调用，`DENY` 会作为 tool result 回灌模型。
 */
public record PermissionResponse(
    String sessionId,
    String turnId,
    String toolUseId,
    PermissionBehavior behavior,
    Optional<String> feedback,
    Optional<PermissionUpdate> permissionUpdate
) {
    public PermissionResponse {
        sessionId = normalizeRequired(sessionId, "sessionId");
        turnId = normalizeRequired(turnId, "turnId");
        toolUseId = normalizeRequired(toolUseId, "toolUseId");
        behavior = behavior == null ? PermissionBehavior.DENY : behavior;
        feedback = feedback == null ? Optional.empty() : feedback.filter(value -> !value.isBlank());
        permissionUpdate = permissionUpdate == null ? Optional.empty() : permissionUpdate;
    }

    private static String normalizeRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
