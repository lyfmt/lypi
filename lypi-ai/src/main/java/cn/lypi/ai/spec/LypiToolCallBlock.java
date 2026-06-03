package cn.lypi.ai.spec;

import java.util.Map;

public record LypiToolCallBlock(
    String toolUseId,
    String toolName,
    String text,
    Map<String, Object> metadata
) implements LypiContentBlock {
    public LypiToolCallBlock {
        metadata = Map.copyOf(metadata);
    }
}
