package cn.lypi.tool.mcp.stdio;

import cn.lypi.contracts.mcp.McpServerConfig;
import cn.lypi.contracts.mcp.McpToolSchema;
import cn.lypi.tool.mcp.McpClient;
import cn.lypi.tool.mcp.McpClientException;
import cn.lypi.tool.mcp.McpToolSchemaMapper;
import cn.lypi.tool.mcp.jsonrpc.LineDelimitedJsonRpcEndpoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 基于 STDIO transport 的 MCP Client。
 */
public final class StdioMcpClient implements McpClient {
    private final McpServerConfig config;
    private final Path cwd;
    private final ObjectMapper jsonMapper;
    private final Consumer<String> diagnostics;
    private final McpToolSchemaMapper schemaMapper;
    private StdioMcpProcess process;
    private LineDelimitedJsonRpcEndpoint endpoint;

    public StdioMcpClient(
        McpServerConfig config,
        Path cwd,
        ObjectMapper jsonMapper,
        Consumer<String> diagnostics
    ) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.cwd = cwd;
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper must not be null");
        this.schemaMapper = new McpToolSchemaMapper(jsonMapper);
        this.diagnostics = diagnostics == null ? message -> {
        } : diagnostics;
    }

    @Override
    public List<McpToolSchema> connect() {
        try {
            process = StdioMcpProcess.start(config, cwd);
            startStderrDrain(process);
            endpoint = new LineDelimitedJsonRpcEndpoint(
                process.inputStream(),
                process.outputStream(),
                jsonMapper,
                config.startupTimeout(),
                diagnostics
            );
            endpoint.start();
            await(endpoint.request("initialize", Map.of(
                "protocolVersion", "2025-06-18",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "ly-pi", "version", "0.0.1-SNAPSHOT")
            ), config.startupTimeout()), config.startupTimeout(), "initialize");
            endpoint.notify("notifications/initialized", Map.of());
            JsonNode toolsResult = await(endpoint.request("tools/list", Map.of(), config.startupTimeout()), config.startupTimeout(), "tools/list");
            return schemaMapper.map(config.name(), toolsResult);
        } catch (RuntimeException exception) {
            close();
            throw exception;
        }
    }

    @Override
    public JsonNode callTool(String toolName, Map<String, Object> arguments) {
        if (endpoint == null) {
            throw new McpClientException("MCP client is not connected: " + config.name());
        }
        return await(endpoint.request("tools/call", Map.of(
            "name", toolName,
            "arguments", arguments == null ? Map.of() : arguments
        ), config.callTimeout()), config.callTimeout(), "tools/call");
    }

    @Override
    public void close() {
        if (endpoint != null) {
            endpoint.close();
        }
        if (process != null) {
            process.close();
        }
    }

    private JsonNode await(CompletableFuture<JsonNode> future, java.time.Duration timeout, String method) {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception exception) {
            throw new McpClientException("MCP " + method + " failed for " + config.name(), exception);
        }
    }

    private void startStderrDrain(StdioMcpProcess process) {
        Thread thread = new Thread(() -> {
            try {
                process.errorStream().transferTo(java.io.OutputStream.nullOutputStream());
            } catch (Exception exception) {
                diagnostics.accept("failed to drain MCP stderr: " + exception.getMessage());
            }
        }, "mcp-stdio-stderr-" + config.name());
        thread.setDaemon(true);
        thread.start();
    }
}
