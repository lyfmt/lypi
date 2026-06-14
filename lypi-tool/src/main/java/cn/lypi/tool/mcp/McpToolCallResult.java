package cn.lypi.tool.mcp;

/**
 * 表示 MCP tools/call 映射后的输出和错误状态。
 */
public record McpToolCallResult(
    String output,
    boolean error
) {}
