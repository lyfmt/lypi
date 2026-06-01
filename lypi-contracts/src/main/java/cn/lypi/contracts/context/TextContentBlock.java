package cn.lypi.contracts.context;

import java.util.Map;

public record TextContentBlock(
    String text,
    Map<String, Object> metadata
) implements ContentBlock {
    public TextContentBlock(String text) {
        this(text, Map.of());
    }

    @Override
    public ContentBlockKind kind() {
        return ContentBlockKind.TEXT;
    }
}
