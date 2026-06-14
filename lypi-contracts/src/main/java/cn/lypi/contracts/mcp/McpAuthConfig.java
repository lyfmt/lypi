package cn.lypi.contracts.mcp;

/**
 * 描述远程 MCP 端点鉴权配置。
 */
public sealed interface McpAuthConfig permits McpNoAuthConfig, McpBearerAuthConfig {
}
