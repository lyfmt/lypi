package cn.lypi.contracts.mcp;

/**
 * 表示远程 MCP 端点不需要鉴权。
 */
public record McpNoAuthConfig() implements McpAuthConfig {
}
