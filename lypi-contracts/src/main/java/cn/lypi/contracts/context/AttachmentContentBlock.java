package cn.lypi.contracts.context;

import java.util.Map;

public record AttachmentContentBlock(
    String attachmentId,
    String text,
    String mediaType,
    Map<String, Object> metadata
) implements ContentBlock {
    public AttachmentContentBlock(String attachmentId, String text, String mediaType) {
        this(attachmentId, text, mediaType, Map.of());
    }

    @Override
    public ContentBlockKind kind() {
        return ContentBlockKind.ATTACHMENT;
    }
}
