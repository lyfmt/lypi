package cn.lypi.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolCallResolverTest {
    @Test
    void resolvesAliasesToCanonicalToolRequestsAndKeepsOriginalName() {
        Tool<Map<String, Object>, String> tool = TestTools.echo("read", List.of("view"), true, true, false);
        DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(tool);
        ToolCallResolver resolver = new ToolCallResolver(registry);

        List<ToolCallResolver.ResolvedCall> calls = resolver.resolve(List.of(
            new ToolUseRequest("toolu_1", "view", Map.of("path", "a.txt"), "msg_1")
        ));

        assertEquals(1, calls.size());
        ToolCallResolver.ResolvedCall call = calls.getFirst();
        assertEquals(0, call.index());
        assertTrue(call.known());
        assertEquals("view", call.originalToolName());
        assertEquals("read", call.request().toolName());
        assertEquals("toolu_1", call.request().toolUseId());
        assertEquals(Map.of("path", "a.txt"), call.request().input());
        assertEquals("msg_1", call.request().parentMessageId());
        assertSame(tool, call.tool());
    }

    @Test
    void keepsCanonicalRequestsUnchangedWhenNameAlreadyMatches() {
        Tool<Map<String, Object>, String> tool = TestTools.echo("read", List.of("view"), true, true, false);
        DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(tool);
        ToolCallResolver resolver = new ToolCallResolver(registry);
        ToolUseRequest request = new ToolUseRequest("toolu_1", "read", Map.of(), "msg_1");

        ToolCallResolver.ResolvedCall call = resolver.resolve(List.of(request)).getFirst();

        assertSame(request, call.request());
        assertEquals("read", call.originalToolName());
        assertTrue(call.known());
    }

    @Test
    void marksUnknownToolCallsWithoutChangingTheRequest() {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        ToolCallResolver resolver = new ToolCallResolver(registry);
        ToolUseRequest request = new ToolUseRequest("toolu_1", "missing", Map.of(), "msg_1");

        ToolCallResolver.ResolvedCall call = resolver.resolve(List.of(request)).getFirst();

        assertEquals(0, call.index());
        assertFalse(call.known());
        assertSame(request, call.request());
        assertEquals("missing", call.originalToolName());
    }
}
