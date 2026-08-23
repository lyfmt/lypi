package cn.lycode.ai.spec;

import java.util.Map;

public record LyCodeToolCallBlock(
    String toolUseId,
    String toolName,
    String text,
    Map<String, Object> metadata
) implements LyCodeContentBlock {
    public LyCodeToolCallBlock {
        metadata = Map.copyOf(metadata);
    }
}
