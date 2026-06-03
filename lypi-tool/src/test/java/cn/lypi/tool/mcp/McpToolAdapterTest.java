package cn.lypi.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.mcp.McpToolSchema;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class McpToolAdapterTest {
    @Test
    void exposesSchemaAndInvokesMcpTool() {
        JsonSchema schema = new JsonSchema(Map.of("type", "object"));
        McpToolSchema mcpSchema = new McpToolSchema("github", "list_issues", "ignored", schema, "List issues");
        AtomicReference<Map<String, Object>> input = new AtomicReference<>();
        McpToolAdapter adapter = new McpToolAdapter(mcpSchema, (serverName, toolName, arguments, context, progress) -> {
            input.set(arguments);
            return Map.of("ok", true);
        });

        ToolResult<Object> result = adapter.execute(Map.of("repo", "ly-pi"), context(), message -> {
        });

        assertEquals("mcp__github__list_issues", adapter.name());
        assertSame(schema, adapter.inputSchema());
        assertEquals(Map.of("repo", "ly-pi"), input.get());
        assertFalse(result.isError());
        assertTrue(result.output().toString().contains("ok=true"));
    }

    @Test
    void mcpFailureReturnsToolError() {
        McpToolSchema schema = new McpToolSchema("github", "list_issues", "", new JsonSchema(Map.of()), "");
        McpToolAdapter adapter = new McpToolAdapter(schema, (serverName, toolName, arguments, context, progress) -> {
            throw new IllegalStateException("offline");
        });

        ToolResult<Object> result = adapter.execute(Map.of(), context(), message -> {
        });

        assertTrue(result.isError());
        assertTrue(result.output().toString().contains("offline"));
    }

    @Test
    void defaultsToWritableAndNotConcurrencySafe() {
        McpToolAdapter adapter = new McpToolAdapter(
            new McpToolSchema("server", "tool", "", new JsonSchema(Map.of()), ""),
            (serverName, toolName, arguments, context, progress) -> "ok"
        );

        assertFalse(adapter.isReadOnly(Map.of()));
        assertFalse(adapter.isConcurrencySafe(Map.of()));
        assertTrue(adapter.isDestructive(Map.of()));
    }

    private ToolUseContext context() {
        return new ToolUseContext("ses_1", "msg_1", Path.of("."), Map.of("toolUseId", "toolu_1"));
    }
}
