package cn.lypi.contracts.model;

public sealed interface AssistantStreamEvent permits
    AssistantStart,
    TextDelta,
    ThinkingDelta,
    ToolCallDelta,
    AssistantDone,
    AssistantError {
}

