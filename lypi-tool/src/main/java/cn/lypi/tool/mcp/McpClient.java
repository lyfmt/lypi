package cn.lypi.tool.mcp;

import cn.lypi.contracts.mcp.McpToolSchema;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/**
 * 连接外部 MCP 端点并调用其 tools。
 */
public interface McpClient extends AutoCloseable {
    /**
     * 初始化连接并返回可用工具。
     */
    List<McpToolSchema> connect();

    /**
     * 调用外部 MCP tool。
     */
    JsonNode callTool(String toolName, Map<String, Object> arguments);

    @Override
    void close();
}
