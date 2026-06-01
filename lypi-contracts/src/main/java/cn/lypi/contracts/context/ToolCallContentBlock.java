package cn.lypi.contracts.context;

import java.util.Map;

public record ToolCallContentBlock(
    String toolUseId,
    String toolName,
    String text,
    Map<String, Object> metadata
) implements ContentBlock {
    public ToolCallContentBlock(String toolUseId, String toolName, String text) {
        this(toolUseId, toolName, text, Map.of());
    }

    @Override
    public ContentBlockKind kind() {
        return ContentBlockKind.TOOL_CALL;
    }
}
