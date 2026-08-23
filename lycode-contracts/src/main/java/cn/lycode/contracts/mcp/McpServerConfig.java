package cn.lycode.contracts.mcp;

import java.time.Duration;

/**
 * 描述一个外部 MCP 端点的连接配置。
 *
 * NOTE: ly-code 只作为 MCP Client 使用该配置；这里的 server 指 MCP 协议中的被连接端。
 */
public record McpServerConfig(
    String name,
    McpTransport transport,
    McpStdioServerConfig stdio,
    McpHttpServerConfig http,
    Duration startupTimeout,
    Duration callTimeout
) {}
