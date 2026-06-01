package cn.lypi.ai;

import cn.lypi.contracts.model.ThinkingLevel;
import java.util.Map;
import java.util.Objects;

public final class ThinkingParameterMapper {
    private ThinkingParameterMapper() {
    }

    public static Map<String, Object> openAiCompatible(ThinkingLevel level) {
        Objects.requireNonNull(level, "level");
        return switch (level) {
            case OFF -> Map.of();
            case MINIMAL -> reasoningEffort("minimal");
            case LOW -> reasoningEffort("low");
            case MEDIUM -> reasoningEffort("medium");
            case HIGH -> reasoningEffort("high");
            case XHIGH -> reasoningEffort("xhigh");
            case MAX -> reasoningEffort("max");
        };
    }

    private static Map<String, Object> reasoningEffort(String value) {
        return Map.of("reasoning_effort", value);
    }
}
