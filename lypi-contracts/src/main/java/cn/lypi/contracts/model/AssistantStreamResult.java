package cn.lypi.contracts.model;

import java.util.List;
import java.util.Optional;

public record AssistantStreamResult(
    String messageId,
    List<AssistantStreamEvent> events,
    Optional<TokenUsage> usage,
    Optional<String> stopReason,
    boolean completed,
    boolean aborted,
    Optional<AssistantError> error
) {
    public AssistantStreamResult {
        messageId = messageId == null ? "" : messageId;
        events = events == null ? List.of() : List.copyOf(events);
        usage = usage == null ? Optional.empty() : usage;
        stopReason = stopReason == null ? Optional.empty() : stopReason;
        error = error == null ? Optional.empty() : error;
    }
}
