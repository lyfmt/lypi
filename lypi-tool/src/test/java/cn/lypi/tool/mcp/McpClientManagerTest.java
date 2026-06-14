package cn.lypi.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.mcp.McpServerConfig;
import cn.lypi.contracts.mcp.McpStdioServerConfig;
import cn.lypi.contracts.mcp.McpToolSchema;
import cn.lypi.contracts.mcp.McpTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpClientManagerTest {
    private final ObjectMapper jsonMapper = new ObjectMapper();

    @Test
    void connectsAllAvailableServersAndReturnsTools() {
        McpClientManager manager = new McpClientManager(Path.of("."), (config, cwd) -> new FakeClient(config.name()));

        List<McpToolSchema> tools = manager.connectAll(List.of(config("github"), config("filesystem")));

        assertEquals(List.of("github", "filesystem"), tools.stream().map(McpToolSchema::serverName).toList());
    }

    @Test
    void skipsFailedServersWithoutBlockingOthers() {
        McpClientManager manager = new McpClientManager(Path.of("."), (config, cwd) -> {
            if (config.name().equals("bad")) {
                throw new IllegalStateException("offline");
            }
            return new FakeClient(config.name());
        });

        List<McpToolSchema> tools = manager.connectAll(List.of(config("bad"), config("ok")));

        assertEquals(List.of("ok"), tools.stream().map(McpToolSchema::serverName).toList());
    }

    @Test
    void invokeDispatchesToConnectedServer() {
        List<Map<String, Object>> calls = new ArrayList<>();
        McpClientManager manager = new McpClientManager(Path.of("."), (config, cwd) -> new FakeClient(config.name()) {
            @Override
            public JsonNode callTool(String toolName, Map<String, Object> arguments) {
                calls.add(Map.of("server", config.name(), "tool", toolName, "arguments", arguments));
                return jsonMapper.valueToTree(Map.of("ok", true));
            }
        });
        manager.connectAll(List.of(config("github")));

        JsonNode result = manager.invoke("github", "list_issues", Map.of("repo", "ly-pi"));

        assertEquals(true, result.path("ok").asBoolean());
        assertEquals("github", calls.getFirst().get("server"));
        assertEquals("list_issues", calls.getFirst().get("tool"));
    }

    @Test
    void invokeFailsWhenServerIsNotConnected() {
        McpClientManager manager = new McpClientManager(Path.of("."), (config, cwd) -> new FakeClient(config.name()));

        assertThrows(McpClientException.class, () -> manager.invoke("missing", "tool", Map.of()));
    }

    private McpServerConfig config(String name) {
        return new McpServerConfig(
            name,
            McpTransport.STDIO,
            new McpStdioServerConfig(List.of("fake"), Map.of()),
            null,
            Duration.ofSeconds(1),
            Duration.ofSeconds(1)
        );
    }

    private static class FakeClient implements McpClient {
        private final String serverName;

        private FakeClient(String serverName) {
            this.serverName = serverName;
        }

        @Override
        public List<McpToolSchema> connect() {
            return List.of(new McpToolSchema(
                serverName,
                "tool",
                McpToolName.format(serverName, "tool"),
                new JsonSchema(Map.of()),
                "tool"
            ));
        }

        @Override
        public JsonNode callTool(String toolName, Map<String, Object> arguments) {
            return new ObjectMapper().valueToTree(Map.of());
        }

        @Override
        public void close() {
        }
    }
}
