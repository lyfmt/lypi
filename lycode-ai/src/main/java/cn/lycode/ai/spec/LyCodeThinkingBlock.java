package cn.lycode.ai.spec;

import java.util.Map;

public record LyCodeThinkingBlock(
    String text,
    Map<String, Object> metadata
) implements LyCodeContentBlock {
    public LyCodeThinkingBlock {
        metadata = Map.copyOf(metadata);
    }
}
