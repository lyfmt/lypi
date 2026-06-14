package cn.lypi.contracts.model;

import java.util.Map;
import java.util.Optional;

public record ProviderConversationState(
    String provider,
    String style,
    Optional<String> previousResponseId,
    Map<String, Object> metadata
) {
    public ProviderConversationState {
        provider = provider == null ? "" : provider;
        style = style == null ? "" : style;
        previousResponseId = previousResponseId == null ? Optional.empty() : previousResponseId;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
