package cn.lypi.contracts.mcp;

import cn.lypi.contracts.common.JsonSchema;

public record McpToolSchema(
    String serverName,
    String toolName,
    String lyPiToolName,
    JsonSchema inputSchema,
    String description
) {}

