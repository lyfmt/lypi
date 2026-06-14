package cn.lypi.ai.spec;

import java.util.List;
import java.util.Map;

public record LypiMessage(
    LypiRole role,
    List<LypiContentBlock> content,
    Map<String, Object> metadata
) {
    public LypiMessage {
        content = List.copyOf(content);
        metadata = Map.copyOf(metadata);
    }
}
