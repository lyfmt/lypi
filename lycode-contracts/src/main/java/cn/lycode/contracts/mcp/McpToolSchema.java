package cn.lycode.contracts.mcp;

import cn.lycode.contracts.common.JsonSchema;

public record McpToolSchema(
    String serverName,
    String toolName,
    String lyCodeToolName,
    JsonSchema inputSchema,
    String description
) {}

