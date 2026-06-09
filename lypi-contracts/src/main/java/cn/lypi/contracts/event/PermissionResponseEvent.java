package cn.lypi.contracts.event;

import java.time.Instant;

public record PermissionResponseEvent(
    String sessionId,
    String requestId,
    String selectedOptionId,
    boolean fromKeyboardCancel,
    Instant timestamp
) implements AgentEvent {
    public PermissionResponseEvent {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        if (selectedOptionId == null || selectedOptionId.isBlank()) {
            throw new IllegalArgumentException("selectedOptionId must not be blank");
        }
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }
}
