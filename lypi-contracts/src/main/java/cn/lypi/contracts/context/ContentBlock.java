package cn.lypi.contracts.context;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Map;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = TextContentBlock.class, name = "text"),
    @JsonSubTypes.Type(value = ThinkingContentBlock.class, name = "thinking"),
    @JsonSubTypes.Type(value = ToolCallContentBlock.class, name = "tool_call"),
    @JsonSubTypes.Type(value = ToolResultContentBlock.class, name = "tool_result"),
    @JsonSubTypes.Type(value = ErrorContentBlock.class, name = "error"),
    @JsonSubTypes.Type(value = AttachmentContentBlock.class, name = "attachment")
})
public sealed interface ContentBlock permits
    TextContentBlock,
    ThinkingContentBlock,
    ToolCallContentBlock,
    ToolResultContentBlock,
    ErrorContentBlock,
    AttachmentContentBlock {
    ContentBlockKind kind();

    String text();

    Map<String, Object> metadata();
}
