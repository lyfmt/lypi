package cn.lypi.tool.mcp;

/**
 * 表示 MCP JSON-RPC 协议错误。
 */
public class McpProtocolException extends McpClientException {
    private final int code;

    public McpProtocolException(int code, String message) {
        super(message);
        this.code = code;
    }

    public McpProtocolException(String message, Throwable cause) {
        super(message, cause);
        this.code = 0;
    }

    /**
     * 返回 JSON-RPC 错误码。
     */
    public int code() {
        return code;
    }
}
