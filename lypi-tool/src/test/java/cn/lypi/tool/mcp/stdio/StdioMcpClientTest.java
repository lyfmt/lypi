package cn.lypi.tool.mcp.stdio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.junit.jupiter.api.io.TempDir;

class StdioMcpClientTest {
    @TempDir
    Path tempDir;

    @Test
    void connectInitializesAndListsTools() {
        StdioMcpClient client = new StdioMcpClient(config(), tempDir, new ObjectMapper(), message -> {
        });

        List<McpToolSchema> tools = client.connect();

        assertEquals(1, tools.size());
        McpToolSchema tool = tools.getFirst();
        assertEquals("fake", tool.serverName());
        assertEquals("echo", tool.toolName());
        assertEquals("mcp__fake__echo", tool.lyPiToolName());
        assertTrue(tool.description().contains("Echo"));
        assertEquals("object", tool.inputSchema().value().get("type"));
        client.close();
    }

    @Test
    void callToolSendsToolsCallAndReturnsResult() {
        StdioMcpClient client = new StdioMcpClient(config(), tempDir, new ObjectMapper(), message -> {
        });
        client.connect();

        JsonNode result = client.callTool("echo", Map.of("text", "hi"));

        assertEquals("hi", result.path("content").get(0).path("text").asText());
        assertEquals(false, result.path("isError").asBoolean());
        client.close();
    }

    @Test
    void closesProcessWhenConnectFails() {
        StdioMcpClient client = new StdioMcpClient(config("fail-initialize"), tempDir, new ObjectMapper(), message -> {
        });

        assertThrows(cn.lypi.tool.mcp.McpClientException.class, client::connect);

        client.close();
    }

    private McpServerConfig config() {
        return config(new String[0]);
    }

    private McpServerConfig config(String... args) {
        return new McpServerConfig(
            "fake",
            McpTransport.STDIO,
            new McpStdioServerConfig(command(args), Map.of()),
            null,
            Duration.ofSeconds(5),
            Duration.ofSeconds(5)
        );
    }

    private List<String> command(String... args) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home")).resolve("bin/java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(FakeStdioMcpServer.class.getName());
        command.addAll(List.of(args));
        return command;
    }
}
