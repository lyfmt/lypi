package cn.lypi.contracts.mcp;

import java.util.List;
import java.util.Map;

/**
 * 描述 STDIO MCP 外部端点配置。
 */
public record McpStdioServerConfig(
    List<String> command,
    Map<String, String> env
) {}
