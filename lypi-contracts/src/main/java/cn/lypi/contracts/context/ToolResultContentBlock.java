package cn.lypi.contracts.context;

import java.util.Map;

public record ToolResultContentBlock(
    String toolUseId,
    String text,
    boolean error,
    Map<String, Object> metadata
) implements ContentBlock {
    public ToolResultContentBlock(String toolUseId, String text, boolean error) {
        this(toolUseId, text, error, Map.of());
    }

    @Override
    public ContentBlockKind kind() {
        return ContentBlockKind.TOOL_RESULT;
    }
}
