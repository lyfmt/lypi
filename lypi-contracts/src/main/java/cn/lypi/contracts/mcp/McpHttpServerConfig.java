package cn.lypi.contracts.mcp;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * 描述远程 MCP HTTP 端点配置。
 *
 * NOTE: 第一版 MCP Client 只消费 STDIO 配置；该配置用于后续远程 MCP 扩展。
 */
public record McpHttpServerConfig(
    URI url,
    Map<String, String> headers,
    McpAuthConfig auth,
    Duration connectTimeout,
    Duration readTimeout
) {}
