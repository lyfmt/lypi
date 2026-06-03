package cn.lypi.ai.spec;

import java.util.Map;
import java.util.Optional;

public record LypiGenerationOptions(
    Optional<Integer> maxOutputTokens,
    Optional<Double> temperature,
    Map<String, Object> metadata
) {
    public LypiGenerationOptions {
        metadata = Map.copyOf(metadata);
    }

    public static LypiGenerationOptions defaults() {
        return new LypiGenerationOptions(Optional.empty(), Optional.empty(), Map.of());
    }
}
