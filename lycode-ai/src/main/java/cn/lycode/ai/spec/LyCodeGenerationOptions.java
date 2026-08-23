package cn.lycode.ai.spec;

import java.util.Map;
import java.util.Optional;

public record LyCodeGenerationOptions(
    Optional<Integer> maxOutputTokens,
    Optional<Double> temperature,
    Map<String, Object> metadata
) {
    public LyCodeGenerationOptions {
        metadata = Map.copyOf(metadata);
    }

    public static LyCodeGenerationOptions defaults() {
        return new LyCodeGenerationOptions(Optional.empty(), Optional.empty(), Map.of());
    }
}
