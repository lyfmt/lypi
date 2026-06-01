package cn.lypi.contracts.context;

import java.util.Map;

public record ThinkingContentBlock(
    String text,
    Map<String, Object> metadata
) implements ContentBlock {
    public ThinkingContentBlock(String text) {
        this(text, Map.of());
    }

    @Override
    public ContentBlockKind kind() {
        return ContentBlockKind.THINKING;
    }
}
