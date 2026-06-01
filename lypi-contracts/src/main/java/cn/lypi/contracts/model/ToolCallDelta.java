package cn.lypi.contracts.model;

import java.util.Map;

public record ToolCallDelta(
    String toolUseId,
    String toolName,
    Map<String, Object> partialInput,
    boolean complete
) implements AssistantStreamEvent {}

