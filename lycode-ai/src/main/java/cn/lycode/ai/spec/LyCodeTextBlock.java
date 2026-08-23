package cn.lycode.ai.spec;

import java.util.Map;

public record LyCodeTextBlock(
    String text,
    Map<String, Object> metadata
) implements LyCodeContentBlock {
    public LyCodeTextBlock {
        metadata = Map.copyOf(metadata);
    }
}
