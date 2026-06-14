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
    Optional<AssistantError> error,
    Optional<ProviderConversationState> providerConversationState
) {
    public AssistantStreamResult(
        String messageId,
        List<AssistantStreamEvent> events,
        Optional<TokenUsage> usage,
        Optional<String> stopReason,
        boolean completed,
        boolean aborted,
        Optional<AssistantError> error
    ) {
        this(messageId, events, usage, stopReason, completed, aborted, error, Optional.empty());
    }

    public AssistantStreamResult {
        messageId = messageId == null ? "" : messageId;
        events = events == null ? List.of() : List.copyOf(events);
        usage = usage == null ? Optional.empty() : usage;
        stopReason = stopReason == null ? Optional.empty() : stopReason;
        error = error == null ? Optional.empty() : error;
        providerConversationState = providerConversationState == null ? Optional.empty() : providerConversationState;
    }
}
