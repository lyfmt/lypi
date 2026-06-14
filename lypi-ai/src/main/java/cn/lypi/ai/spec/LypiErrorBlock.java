package cn.lypi.ai.spec;

import java.util.Map;

public record LypiErrorBlock(
    String errorId,
    String text,
    Map<String, Object> metadata
) implements LypiContentBlock {
    public LypiErrorBlock {
        metadata = Map.copyOf(metadata);
    }
}
