package cn.lypi.contracts.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.tool.InterruptBehavior;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DefaultToolHookRuntimeTest {
    @Test
    void toolHookDefaultsToNoOpBeforeAndAfter() {
        ToolHook hook = new ToolHook() {
        };

        BeforeToolHookResult beforeResult = hook.beforeToolCall(beforeContext());
        AfterToolHookResult afterResult = hook.afterToolCall(afterContext(result("original")));

        assertFalse(beforeResult.blocked());
        assertNull(beforeResult.message());
        assertTrue(afterResult.replacement().isEmpty());
    }

    @Test
    void noopRuntimeAllowsBeforeAndKeepsAfter() {
        ToolHookRuntime runtime = ToolHookRuntime.noop();

        BeforeToolHookResult beforeResult = runtime.beforeToolCall(beforeContext());
        Optional<ToolResult<?>> afterResult = runtime.afterToolCall(afterContext(result("original")));

        assertFalse(beforeResult.blocked());
        assertNull(beforeResult.message());
        assertTrue(afterResult.isEmpty());
    }

    @Test
    void beforeStopsAtFirstBlockingHook() {
        ToolHook first = ToolHook.before(context -> BeforeToolHookResult.block("blocked"));
        ToolHook second = ToolHook.before(context -> {
            fail("阻断后不应继续执行后续 before hook");
            return BeforeToolHookResult.allow();
        });

        BeforeToolHookResult result = new DefaultToolHookRuntime(List.of(first, second))
            .beforeToolCall(beforeContext());

        assertTrue(result.blocked());
        assertEquals("blocked", result.message());
    }

    @Test
    void beforeAllowsWhenNoHookBlocks() {
        ToolHook first = ToolHook.before(context -> BeforeToolHookResult.allow());
        ToolHook second = ToolHook.before(context -> BeforeToolHookResult.allow());

        BeforeToolHookResult result = new DefaultToolHookRuntime(List.of(first, second))
            .beforeToolCall(beforeContext());

        assertFalse(result.blocked());
        assertNull(result.message());
    }

    @Test
    void afterAppliesResultReplacementInOrder() {
        ToolHook first = ToolHook.after(context -> AfterToolHookResult.replace(result("first")));
        ToolHook second = ToolHook.after(context ->
            AfterToolHookResult.replace(result(context.result().output() + "-second"))
        );

        ToolResult<?> result = new DefaultToolHookRuntime(List.of(first, second))
            .afterToolCall(afterContext(result("original")))
            .orElseThrow();

        assertEquals("first-second", result.output());
    }

    @Test
    void afterReturnsEmptyWhenHooksKeepOriginal() {
        ToolHook first = ToolHook.after(context -> AfterToolHookResult.keep());
        ToolHook second = ToolHook.after(context -> AfterToolHookResult.keep());

        Optional<ToolResult<?>> result = new DefaultToolHookRuntime(List.of(first, second))
            .afterToolCall(afterContext(result("original")));

        assertTrue(result.isEmpty());
    }

    @Test
    void runtimeDefensivelyCopiesHookList() {
        List<ToolHook> hooks = new ArrayList<>();
        hooks.add(ToolHook.before(context -> BeforeToolHookResult.allow()));

        DefaultToolHookRuntime runtime = new DefaultToolHookRuntime(hooks);

        hooks.clear();
        hooks.add(ToolHook.before(context -> BeforeToolHookResult.block("late-block")));

        BeforeToolHookResult result = runtime.beforeToolCall(beforeContext());

        assertFalse(result.blocked());
    }

    @Test
    void beforeContextNormalizesInputForContextAndRequest() {
        Map<String, Object> mutableInput = new LinkedHashMap<>();
        mutableInput.put("value", "demo");
        ToolUseRequest request = new ToolUseRequest("toolu_test", "demo-tool", mutableInput, "msg_parent");

        BeforeToolHookContext context = new BeforeToolHookContext(request, tool(), mutableInput, toolContext());

        mutableInput.put("late", "mutation");

        assertEquals(Map.of("value", "demo"), context.input());
        assertEquals(context.input(), context.request().input());
        assertSame(context.input(), context.request().input());
        assertNotSame(mutableInput, context.input());
        assertThrows(UnsupportedOperationException.class, () -> context.input().put("extra", "x"));
        assertThrows(UnsupportedOperationException.class, () -> context.request().input().put("extra", "x"));
    }

    @Test
    void afterContextNormalizesInputForContextAndRequest() {
        Map<String, Object> mutableInput = new LinkedHashMap<>();
        mutableInput.put("value", "demo");
        ToolUseRequest request = new ToolUseRequest("toolu_test", "demo-tool", mutableInput, "msg_parent");

        AfterToolHookContext context = new AfterToolHookContext(
            request,
            tool(),
            mutableInput,
            toolContext(),
            result("original")
        );

        mutableInput.put("late", "mutation");

        assertEquals(Map.of("value", "demo"), context.input());
        assertEquals(context.input(), context.request().input());
        assertSame(context.input(), context.request().input());
        assertNotSame(mutableInput, context.input());
        assertThrows(UnsupportedOperationException.class, () -> context.input().put("extra", "x"));
        assertThrows(UnsupportedOperationException.class, () -> context.request().input().put("extra", "x"));
    }

    private BeforeToolHookContext beforeContext() {
        return new BeforeToolHookContext(
            request(),
            tool(),
            Map.of("value", "demo"),
            toolContext()
        );
    }

    private AfterToolHookContext afterContext(ToolResult<?> result) {
        return new AfterToolHookContext(
            request(),
            tool(),
            Map.of("value", "demo"),
            toolContext(),
            result
        );
    }

    private ToolUseRequest request() {
        return new ToolUseRequest("toolu_test", "demo-tool", Map.of("value", "demo"), "msg_parent");
    }

    private ToolUseContext toolContext() {
        return new ToolUseContext("ses_test", "msg_test", Path.of("."), Map.of("traceId", "trace-1"));
    }

    private Tool<String, String> tool() {
        return new DemoTool();
    }

    private ToolResult<String> result(String output) {
        return new ToolResult<>(output, false, List.of(), Optional.empty());
    }

    private static final class DemoTool implements Tool<String, String> {
        @Override
        public String name() {
            return "demo-tool";
        }

        @Override
        public List<String> aliases() {
            return List.of();
        }

        @Override
        public JsonSchema inputSchema() {
            return new JsonSchema(Map.of("type", "object"));
        }

        @Override
        public ValidationResult validateInput(String input, ToolUseContext context) {
            return new ValidationResult(true, List.of());
        }

        @Override
        public PermissionDecision checkPermissions(String input, ToolUseContext context) {
            return new PermissionDecision(
                PermissionBehavior.ALLOW,
                PermissionDecisionReason.TOOL_SPECIFIC,
                "allowed",
                Optional.empty(),
                Map.of()
            );
        }

        @Override
        public ToolResult<String> execute(String input, ToolUseContext context, ProgressSink progress) {
            return new ToolResult<>(input, false, List.of(), Optional.empty());
        }

        @Override
        public InterruptBehavior interruptBehavior() {
            return InterruptBehavior.CANCEL;
        }

        @Override
        public boolean isReadOnly(String input) {
            return true;
        }

        @Override
        public boolean isConcurrencySafe(String input) {
            return true;
        }

        @Override
        public boolean isDestructive(String input) {
            return false;
        }

        @Override
        public int maxResultSize() {
            return 1024;
        }

        @Override
        public String renderForUser(String input) {
            return input;
        }

        @Override
        public AgentMessage serializeForContext(String output) {
            throw new UnsupportedOperationException("not needed for hook runtime tests");
        }
    }
}
