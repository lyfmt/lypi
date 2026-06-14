package cn.lypi.tool.mcp;

/**
 * 表示 MCP Client 执行失败。
 */
public class McpClientException extends RuntimeException {
    public McpClientException(String message) {
        super(message);
    }

    public McpClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
