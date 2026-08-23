package cn.lycode.ai.spec;

import java.util.Map;

public record LyCodeAttachmentBlock(
    String attachmentId,
    String text,
    String mediaType,
    Map<String, Object> metadata
) implements LyCodeContentBlock {
    public LyCodeAttachmentBlock {
        metadata = Map.copyOf(metadata);
    }
}
