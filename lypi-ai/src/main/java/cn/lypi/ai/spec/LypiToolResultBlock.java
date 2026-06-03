package cn.lypi.ai.spec;

import java.util.Map;

public record LypiToolResultBlock(
    String toolUseId,
    String text,
    boolean error,
    Map<String, Object> metadata
) implements LypiContentBlock {
    public LypiToolResultBlock {
        metadata = Map.copyOf(metadata);
    }
}
