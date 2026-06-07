package cn.lypi.contracts.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = AssistantStart.class, name = "assistant_start"),
    @JsonSubTypes.Type(value = TextDelta.class, name = "text_delta"),
    @JsonSubTypes.Type(value = ThinkingDelta.class, name = "thinking_delta"),
    @JsonSubTypes.Type(value = ToolCallDelta.class, name = "tool_call_delta"),
    @JsonSubTypes.Type(value = AssistantDone.class, name = "assistant_done"),
    @JsonSubTypes.Type(value = AssistantError.class, name = "assistant_error"),
    @JsonSubTypes.Type(value = ProviderRetryNotice.class, name = "provider_retry")
})
public sealed interface AssistantStreamEvent permits
    AssistantStart,
    TextDelta,
    ThinkingDelta,
    ToolCallDelta,
    AssistantDone,
    AssistantError,
    ProviderRetryNotice {
}
