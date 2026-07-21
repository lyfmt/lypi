package cn.lypi.contracts.runtime;

import java.util.Map;

public record CompactStateBackfillItem(
    String attachmentId,
    String title,
    String content,
    Map<String, String> metadata
) {
    public CompactStateBackfillItem {
        attachmentId = attachmentId == null ? "" : attachmentId;
        title = title == null ? "" : title;
        content = content == null ? "" : content;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
