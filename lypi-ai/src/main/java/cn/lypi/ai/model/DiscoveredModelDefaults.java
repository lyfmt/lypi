package cn.lypi.ai.model;

import cn.lypi.contracts.model.CostProfile;
import java.util.Map;
import java.util.Objects;

public record DiscoveredModelDefaults(
    int contextWindow,
    int maxOutputTokens,
    boolean supportsThinking,
    boolean supportsImageInput,
    CostProfile costProfile,
    Map<String, Object> compat
) {
    public static final int DEFAULT_CONTEXT_WINDOW = 256_000;
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 8_192;
    public static final boolean DEFAULT_SUPPORTS_THINKING = true;
    public static final boolean DEFAULT_SUPPORTS_IMAGE_INPUT = true;

    public DiscoveredModelDefaults {
        if (contextWindow <= 0 || maxOutputTokens <= 0) {
            throw new IllegalArgumentException("Model discovery default token limits must be positive.");
        }
        costProfile = Objects.requireNonNull(costProfile, "costProfile");
        compat = Map.copyOf(Objects.requireNonNull(compat, "compat"));
    }
}
