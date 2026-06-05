package cn.lypi.contracts.event;

import cn.lypi.contracts.context.ContentBlockKind;
import java.util.Map;

public record MessageBlockSnapshot(
    String blockId,
    ContentBlockKind blockKind,
    String text,
    Map<String, Object> metadata
) {
    public MessageBlockSnapshot {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
