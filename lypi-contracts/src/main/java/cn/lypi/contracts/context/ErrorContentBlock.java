package cn.lypi.contracts.context;

import java.util.Map;

public record ErrorContentBlock(
    String errorId,
    String text,
    Map<String, Object> metadata
) implements ContentBlock {
    public ErrorContentBlock(String errorId, String text) {
        this(errorId, text, Map.of());
    }

    @Override
    public ContentBlockKind kind() {
        return ContentBlockKind.ERROR;
    }
}
