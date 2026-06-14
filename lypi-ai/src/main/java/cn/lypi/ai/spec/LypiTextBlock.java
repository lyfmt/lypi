package cn.lypi.ai.spec;

import java.util.Map;

public record LypiTextBlock(
    String text,
    Map<String, Object> metadata
) implements LypiContentBlock {
    public LypiTextBlock {
        metadata = Map.copyOf(metadata);
    }
}
