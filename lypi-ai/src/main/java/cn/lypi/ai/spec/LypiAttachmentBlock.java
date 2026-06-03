package cn.lypi.ai.spec;

import java.util.Map;

public record LypiAttachmentBlock(
    String attachmentId,
    String text,
    String mediaType,
    Map<String, Object> metadata
) implements LypiContentBlock {
    public LypiAttachmentBlock {
        metadata = Map.copyOf(metadata);
    }
}
