package cn.lypi.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.hook.AfterToolHookResult;
import cn.lypi.contracts.hook.BeforeToolHookResult;
import cn.lypi.contracts.hook.ToolHookRuntime;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ToolHookExecutionInterceptorTest {
    @Test
    void beforeHookBlocksThroughInterceptor() {
        ToolHookRuntime runtime = new ToolHookRuntime() {
            @Override
            public BeforeToolHookResult beforeToolCall(cn.lypi.contracts.hook.BeforeToolHookContext context) {
                return BeforeToolHookResult.block("hook denied");
            }

            @Override
            public java.util.Optional<ToolResult<?>> afterToolCall(cn.lypi.contracts.hook.AfterToolHookContext context) {
                return java.util.Optional.empty();
            }
        };
        ToolHookExecutionInterceptor interceptor = new ToolHookExecutionInterceptor(runtime);

        ToolExecutionInterceptor.BeforeResult result = interceptor.beforeExecute(
            request(Map.of()),
            tool(),
            context()
        );

        assertTrue(result.blocked());
        assertEquals("hook denied", result.message());
    }

    @Test
    void afterHookCanReplaceToolResult() {
        ToolHookRuntime runtime = new ToolHookRuntime() {
            @Override
            public BeforeToolHookResult beforeToolCall(cn.lypi.contracts.hook.BeforeToolHookContext context) {
                return BeforeToolHookResult.allow();
            }

            @Override
            public java.util.Optional<ToolResult<?>> afterToolCall(cn.lypi.contracts.hook.AfterToolHookContext context) {
                return AfterToolHookResult.replace(TestTools.result(context.request().toolUseId(), "rewritten", false))
                    .replacement();
            }
        };
        ToolHookExecutionInterceptor interceptor = new ToolHookExecutionInterceptor(runtime);

        ToolResult<?> result = interceptor.afterExecute(
            request(Map.of()),
            tool(),
            context(),
            TestTools.result("toolu_1", "original", false)
        );

        assertEquals("rewritten", result.output());
    }

    @Test
    void nullRuntimeFallsBackToNoop() {
        ToolHookExecutionInterceptor interceptor = new ToolHookExecutionInterceptor(null);
        ToolUseRequest request = request(Map.of("text", "hello"));
        ToolResult<?> original = TestTools.result(request.toolUseId(), "original", false);

        ToolExecutionInterceptor.BeforeResult beforeResult = interceptor.beforeExecute(request, tool(), context());
        ToolResult<?> afterResult = interceptor.afterExecute(request, tool(), context(), original);

        assertFalse(beforeResult.blocked());
        assertEquals("", beforeResult.message());
        assertSameResult(original, afterResult);
    }

    @Test
    void contextsSeeCanonicalSnapshotInput() {
        AtomicReference<Map<String, Object>> beforeInput = new AtomicReference<>();
        AtomicReference<Map<String, Object>> beforeRequestInput = new AtomicReference<>();
        AtomicReference<Map<String, Object>> afterInput = new AtomicReference<>();
        AtomicReference<Map<String, Object>> afterRequestInput = new AtomicReference<>();
        ToolHookRuntime runtime = new ToolHookRuntime() {
            @Override
            public BeforeToolHookResult beforeToolCall(cn.lypi.contracts.hook.BeforeToolHookContext context) {
                beforeInput.set(context.input());
                beforeRequestInput.set(context.request().input());
                return BeforeToolHookResult.allow();
            }

            @Override
            public java.util.Optional<ToolResult<?>> afterToolCall(cn.lypi.contracts.hook.AfterToolHookContext context) {
                afterInput.set(context.input());
                afterRequestInput.set(context.request().input());
                return java.util.Optional.empty();
            }
        };
        ToolHookExecutionInterceptor interceptor = new ToolHookExecutionInterceptor(runtime);
        Map<String, Object> mutableInput = new LinkedHashMap<>();
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("flag", true);
        mutableInput.put("nested", nested);
        ToolUseRequest request = request(mutableInput);

        interceptor.beforeExecute(request, tool(), context());
        interceptor.afterExecute(request, tool(), context(), TestTools.result(request.toolUseId(), "original", false));
        nested.put("late", "mutation");
        mutableInput.put("newKey", "newValue");

        assertEquals(beforeInput.get(), beforeRequestInput.get());
        assertEquals(afterInput.get(), afterRequestInput.get());
        assertEquals(beforeInput.get(), afterInput.get());
        assertNotSame(mutableInput, beforeInput.get());
        assertNotSame(mutableInput, beforeRequestInput.get());
        assertFalse(beforeInput.get().containsKey("newKey"));
        assertFalse(beforeRequestInput.get().containsKey("newKey"));
        Map<?, ?> beforeNested = (Map<?, ?>) beforeInput.get().get("nested");
        Map<?, ?> afterNested = (Map<?, ?>) afterInput.get().get("nested");
        assertFalse(beforeNested.containsKey("late"));
        assertFalse(afterNested.containsKey("late"));
    }

    private ToolUseRequest request(Map<String, Object> input) {
        return new ToolUseRequest("toolu_1", "echo", input, "msg_1");
    }

    private cn.lypi.contracts.tool.Tool<Map<String, Object>, String> tool() {
        return TestTools.echo("echo", List.of(), true, true, false);
    }

    private ToolUseContext context() {
        return TestTools.toolContext(PermissionMode.DEFAULT_EXECUTE);
    }

    private void assertSameResult(ToolResult<?> expected, ToolResult<?> actual) {
        assertEquals(expected.output(), actual.output());
        assertEquals(expected.isError(), actual.isError());
        assertEquals(expected.newMessages(), actual.newMessages());
        assertEquals(expected.replacement(), actual.replacement());
    }
}
