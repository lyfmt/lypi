package cn.lycode.ai.spec;

import java.util.Map;

public record LyCodeToolResultBlock(
    String toolUseId,
    String text,
    boolean error,
    Map<String, Object> metadata
) implements LyCodeContentBlock {
    public LyCodeToolResultBlock {
        metadata = Map.copyOf(metadata);
    }
}
