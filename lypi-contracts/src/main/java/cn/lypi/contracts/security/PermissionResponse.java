package cn.lypi.contracts.security;

import java.time.Instant;

public record PermissionResponse(
    String sessionId,
    String requestId,
    String selectedOptionId,
    boolean fromKeyboardCancel,
    Instant timestamp
) {
    public PermissionResponse {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        if (selectedOptionId == null || selectedOptionId.isBlank()) {
            throw new IllegalArgumentException("selectedOptionId must not be blank");
        }
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }
}
