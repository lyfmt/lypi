package cn.lypi.ai.model;

import java.util.Optional;
import java.util.OptionalInt;

public record DiscoveredModel(
    String modelId,
    OptionalInt contextWindow,
    OptionalInt maxOutputTokens,
    Optional<Boolean> supportsThinking,
    Optional<Boolean> supportsImageInput
) {
    public DiscoveredModel {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId is required");
        }
        contextWindow = contextWindow == null ? OptionalInt.empty() : contextWindow;
        maxOutputTokens = maxOutputTokens == null ? OptionalInt.empty() : maxOutputTokens;
        supportsThinking = supportsThinking == null ? Optional.empty() : supportsThinking;
        supportsImageInput = supportsImageInput == null ? Optional.empty() : supportsImageInput;
    }

    public static DiscoveredModel idOnly(String modelId) {
        return new DiscoveredModel(
            modelId,
            OptionalInt.empty(),
            OptionalInt.empty(),
            Optional.empty(),
            Optional.empty()
        );
    }
}
