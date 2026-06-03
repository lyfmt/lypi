package cn.lypi.ai.spec;

import java.util.Map;

public record LypiThinkingBlock(
    String text,
    Map<String, Object> metadata
) implements LypiContentBlock {
    public LypiThinkingBlock {
        metadata = Map.copyOf(metadata);
    }
}
