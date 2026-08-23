package cn.lycode.ai.spec;

import java.util.Map;

public record LyCodeErrorBlock(
    String errorId,
    String text,
    Map<String, Object> metadata
) implements LyCodeContentBlock {
    public LyCodeErrorBlock {
        metadata = Map.copyOf(metadata);
    }
}
