package cn.lypi.tool.mcp;

import cn.lypi.contracts.mcp.McpServerConfig;
import cn.lypi.contracts.mcp.McpToolSchema;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.tool.ToolUseContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 管理多个外部 MCP 端点的 Client 会话。
 */
public final class McpClientManager implements AutoCloseable {
    private final Path cwd;
    private final McpClientFactory clientFactory;
    private final McpToolResultMapper resultMapper;
    private final Map<String, McpClient> clients = new LinkedHashMap<>();

    public McpClientManager(Path cwd, McpClientFactory clientFactory) {
        this(cwd, clientFactory, new McpToolResultMapper(new ObjectMapper()));
    }

    public McpClientManager(Path cwd, McpClientFactory clientFactory, McpToolResultMapper resultMapper) {
        this.cwd = cwd;
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory must not be null");
        this.resultMapper = Objects.requireNonNull(resultMapper, "resultMapper must not be null");
    }

    /**
     * 连接所有可用端点并返回发现到的工具。
     */
    public List<McpToolSchema> connectAll(List<McpServerConfig> configs) {
        List<McpToolSchema> tools = new ArrayList<>();
        if (configs == null) {
            return tools;
        }
        for (McpServerConfig config : configs) {
            McpClient client = null;
            try {
                client = clientFactory.create(config, cwd);
                List<McpToolSchema> schemas = client.connect();
                clients.put(config.name(), client);
                tools.addAll(schemas);
            } catch (RuntimeException exception) {
                if (client != null) {
                    client.close();
                }
                // NOTE: 单个 MCP 端点失败不能阻断本地工具或其他端点。
            }
        }
        return tools;
    }

    /**
     * 调用已连接端点中的指定 tool。
     */
    public JsonNode invoke(String serverName, String toolName, Map<String, Object> arguments) {
        McpClient client = clients.get(serverName);
        if (client == null) {
            throw new McpClientException("MCP server not connected: " + serverName);
        }
        try {
            return client.callTool(toolName, arguments == null ? Map.of() : arguments);
        } catch (RuntimeException exception) {
            throw exception instanceof McpClientException
                ? exception
                : new McpClientException("MCP tool call failed: " + serverName + "/" + toolName, exception);
        }
    }

    /**
     * 调用已连接端点中的指定 tool，并映射为 ly-pi tool result 输出。
     */
    public Object invoke(
        String serverName,
        String toolName,
        Map<String, Object> arguments,
        ToolUseContext context,
        ProgressSink progress
    ) {
        return resultMapper.mapResult(invoke(serverName, toolName, arguments));
    }

    @Override
    public void close() {
        clients.values().forEach(McpClient::close);
        clients.clear();
    }

    @FunctionalInterface
    public interface McpClientFactory {
        /**
         * 创建绑定到指定 cwd 的 MCP client。
         */
        McpClient create(McpServerConfig config, Path cwd);
    }
}
