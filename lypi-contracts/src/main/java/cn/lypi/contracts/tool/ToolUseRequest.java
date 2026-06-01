package cn.lypi.contracts.tool;

import java.util.Map;

public record ToolUseRequest(
    String toolUseId,
    String toolName,
    Map<String, Object> input,
    String parentMessageId
) {}

