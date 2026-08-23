package cn.lycode.ai.spec;

import java.util.List;
import java.util.Map;

public record LyCodeMessage(
    LyCodeRole role,
    List<LyCodeContentBlock> content,
    Map<String, Object> metadata
) {
    public LyCodeMessage {
        content = List.copyOf(content);
        metadata = Map.copyOf(metadata);
    }
}
