package cn.lypi.contracts.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.EventConsumer;
import cn.lypi.contracts.event.EventFilter;
import cn.lypi.contracts.event.EventSubscription;
import cn.lypi.contracts.event.HookEndEvent;
import cn.lypi.contracts.event.HookStartEvent;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    void beforePublishesStartAndBlockedEndEvent() {
        CapturingEventBus events = new CapturingEventBus();
        ToolHook hook = ToolHook.before(context -> BeforeToolHookResult.block("denied"));

        BeforeToolHookResult result = new DefaultToolHookRuntime(List.of(hook), events)
            .beforeToolCall(beforeContext());

        assertTrue(result.blocked());
        assertEquals(2, events.events.size());
        HookStartEvent start = assertInstanceOf(HookStartEvent.class, events.events.get(0));
        HookEndEvent end = assertInstanceOf(HookEndEvent.class, events.events.get(1));
        assertEquals(HookPhase.BEFORE_TOOL_CALL, start.phase());
        assertEquals("ses_test", start.sessionId());
        assertEquals("toolu_test", start.toolUseId());
        assertEquals("msg_parent", start.parentMessageId());
        assertEquals("demo-tool", start.toolName());
        assertEquals(HookRunStatus.BLOCKED, end.status());
        assertEquals("denied", end.message());
        assertEquals(start.hookRunId(), end.hookRunId());
    }

    @Test
    void afterPublishesReplacedEndEvent() {
        CapturingEventBus events = new CapturingEventBus();
        ToolHook hook = ToolHook.after(context -> AfterToolHookResult.replace(result("rewritten")));

        ToolResult<?> result = new DefaultToolHookRuntime(List.of(hook), events)
            .afterToolCall(afterContext(result("original")))
            .orElseThrow();

        assertEquals("rewritten", result.output());
        assertEquals(2, events.events.size());
        HookEndEvent end = assertInstanceOf(HookEndEvent.class, events.events.get(1));
        assertEquals(HookPhase.AFTER_TOOL_CALL, end.phase());
        assertEquals(HookRunStatus.REPLACED, end.status());
    }

    @Test
    void hookFailurePublishesFailedEndThenRethrows() {
        CapturingEventBus events = new CapturingEventBus();
        ToolHook hook = ToolHook.before(context -> {
            throw new IllegalStateException("boom");
        });

        assertThrows(IllegalStateException.class, () -> new DefaultToolHookRuntime(List.of(hook), events)
            .beforeToolCall(beforeContext()));

        assertEquals(2, events.events.size());
        HookEndEvent end = assertInstanceOf(HookEndEvent.class, events.events.get(1));
        assertEquals(HookRunStatus.FAILED, end.status());
        assertEquals("boom", end.message());
    }

    @Test
    void eventPublishFailureDoesNotBlockHookExecution() {
        EventBus failingEvents = new EventBus() {
            @Override
            public void publish(AgentEvent event) {
                throw new IllegalStateException("event bus failed");
            }

            @Override
            public EventSubscription subscribe(EventFilter filter, EventConsumer consumer) {
                return () -> {
                };
            }
        };
        ToolHook hook = ToolHook.before(context -> BeforeToolHookResult.allow());

        BeforeToolHookResult result = new DefaultToolHookRuntime(List.of(hook), failingEvents)
            .beforeToolCall(beforeContext());

        assertFalse(result.blocked());
    }

    @Test
    void missingAuditMetadataDoesNotBlockHookExecution() {
        CapturingEventBus events = new CapturingEventBus();
        ToolUseRequest request = new ToolUseRequest(null, null, Map.of("value", "demo"), null);
        ToolUseContext context = new ToolUseContext(null, null, Path.of("."), Map.of());
        BeforeToolHookContext hookContext = new BeforeToolHookContext(request, tool(), Map.of("value", "demo"), context);
        ToolHook hook = ToolHook.before(ignored -> BeforeToolHookResult.allow());

        BeforeToolHookResult result = new DefaultToolHookRuntime(List.of(hook), events)
            .beforeToolCall(hookContext);

        assertFalse(result.blocked());
        HookStartEvent start = assertInstanceOf(HookStartEvent.class, events.events.getFirst());
        assertEquals("session_unknown", start.sessionId());
        assertEquals("toolu_unknown", start.toolUseId());
        assertEquals("tool_unknown", start.toolName());
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
        assertNotSame(mutableInput, context.input());
        assertThrows(UnsupportedOperationException.class, () -> context.input().put("extra", "x"));
        assertThrows(UnsupportedOperationException.class, () -> context.request().input().put("extra", "x"));
    }

    @Test
    void beforeContextDeepCopiesNestedInputForContextAndRequest() {
        List<Object> nestedList = new ArrayList<>(List.of("first", "second"));
        Map<String, Object> nestedMap = new LinkedHashMap<>();
        nestedMap.put("flag", true);
        nestedMap.put("items", nestedList);
        Map<String, Object> mutableInput = new LinkedHashMap<>();
        mutableInput.put("nested", nestedMap);

        BeforeToolHookContext context = new BeforeToolHookContext(
            new ToolUseRequest("toolu_test", "demo-tool", mutableInput, "msg_parent"),
            tool(),
            mutableInput,
            toolContext()
        );

        nestedList.add("late-item");
        nestedMap.put("late-key", "late-value");

        Map<?, ?> contextNestedMap = (Map<?, ?>) context.input().get("nested");
        List<?> contextNestedList = (List<?>) contextNestedMap.get("items");
        Map<?, ?> requestNestedMap = (Map<?, ?>) context.request().input().get("nested");
        List<?> requestNestedList = (List<?>) requestNestedMap.get("items");

        assertEquals(List.of("first", "second"), contextNestedList);
        assertEquals(List.of("first", "second"), requestNestedList);
        assertFalse(contextNestedMap.containsKey("late-key"));
        assertFalse(requestNestedMap.containsKey("late-key"));
        assertNotSame(nestedMap, contextNestedMap);
        assertNotSame(nestedList, contextNestedList);
        assertThrows(UnsupportedOperationException.class, () -> putEntry(contextNestedMap, "extra", "x"));
        assertThrows(UnsupportedOperationException.class, () -> addItem(contextNestedList, "x"));
        assertThrows(UnsupportedOperationException.class, () -> putEntry(requestNestedMap, "extra", "x"));
        assertThrows(UnsupportedOperationException.class, () -> addItem(requestNestedList, "x"));
    }

    @Test
    void beforeContextPreservesTopLevelNullValues() {
        Map<String, Object> mutableInput = new LinkedHashMap<>();
        mutableInput.put("nullable", null);

        BeforeToolHookContext context = new BeforeToolHookContext(
            new ToolUseRequest("toolu_test", "demo-tool", mutableInput, "msg_parent"),
            tool(),
            mutableInput,
            toolContext()
        );

        assertTrue(context.input().containsKey("nullable"));
        assertNull(context.input().get("nullable"));
        assertTrue(context.request().input().containsKey("nullable"));
        assertNull(context.request().input().get("nullable"));
        assertThrows(UnsupportedOperationException.class, () -> context.input().put("extra", "x"));
        assertThrows(UnsupportedOperationException.class, () -> context.request().input().put("extra", "x"));
    }

    @Test
    void beforeContextPreservesNullsInsideNestedMapAndList() {
        List<Object> nestedList = new ArrayList<>();
        nestedList.add(null);
        nestedList.add("second");
        Map<String, Object> nestedMap = new LinkedHashMap<>();
        nestedMap.put("nullable", null);
        nestedMap.put("items", nestedList);
        Map<String, Object> mutableInput = new LinkedHashMap<>();
        mutableInput.put("nested", nestedMap);

        BeforeToolHookContext context = new BeforeToolHookContext(
            new ToolUseRequest("toolu_test", "demo-tool", mutableInput, "msg_parent"),
            tool(),
            mutableInput,
            toolContext()
        );

        Map<?, ?> contextNestedMap = (Map<?, ?>) context.input().get("nested");
        List<?> contextNestedList = (List<?>) contextNestedMap.get("items");
        Map<?, ?> requestNestedMap = (Map<?, ?>) context.request().input().get("nested");
        List<?> requestNestedList = (List<?>) requestNestedMap.get("items");

        assertTrue(contextNestedMap.containsKey("nullable"));
        assertNull(contextNestedMap.get("nullable"));
        assertEquals(Arrays.asList(null, "second"), contextNestedList);
        assertTrue(requestNestedMap.containsKey("nullable"));
        assertNull(requestNestedMap.get("nullable"));
        assertEquals(Arrays.asList(null, "second"), requestNestedList);
        assertThrows(UnsupportedOperationException.class, () -> putEntry(contextNestedMap, "extra", "x"));
        assertThrows(UnsupportedOperationException.class, () -> addItem(contextNestedList, "x"));
        assertThrows(UnsupportedOperationException.class, () -> putEntry(requestNestedMap, "extra", "x"));
        assertThrows(UnsupportedOperationException.class, () -> addItem(requestNestedList, "x"));
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
        assertNotSame(mutableInput, context.input());
        assertThrows(UnsupportedOperationException.class, () -> context.input().put("extra", "x"));
        assertThrows(UnsupportedOperationException.class, () -> context.request().input().put("extra", "x"));
    }

    @Test
    void afterContextDeepCopiesNestedInputForContextAndRequest() {
        List<Object> nestedList = new ArrayList<>(List.of("first", "second"));
        Map<String, Object> nestedMap = new LinkedHashMap<>();
        nestedMap.put("flag", true);
        nestedMap.put("items", nestedList);
        Map<String, Object> mutableInput = new LinkedHashMap<>();
        mutableInput.put("nested", nestedMap);

        AfterToolHookContext context = new AfterToolHookContext(
            new ToolUseRequest("toolu_test", "demo-tool", mutableInput, "msg_parent"),
            tool(),
            mutableInput,
            toolContext(),
            result("original")
        );

        nestedList.add("late-item");
        nestedMap.put("late-key", "late-value");

        Map<?, ?> contextNestedMap = (Map<?, ?>) context.input().get("nested");
        List<?> contextNestedList = (List<?>) contextNestedMap.get("items");
        Map<?, ?> requestNestedMap = (Map<?, ?>) context.request().input().get("nested");
        List<?> requestNestedList = (List<?>) requestNestedMap.get("items");

        assertEquals(List.of("first", "second"), contextNestedList);
        assertEquals(List.of("first", "second"), requestNestedList);
        assertFalse(contextNestedMap.containsKey("late-key"));
        assertFalse(requestNestedMap.containsKey("late-key"));
        assertNotSame(nestedMap, contextNestedMap);
        assertNotSame(nestedList, contextNestedList);
        assertThrows(UnsupportedOperationException.class, () -> putEntry(contextNestedMap, "extra", "x"));
        assertThrows(UnsupportedOperationException.class, () -> addItem(contextNestedList, "x"));
        assertThrows(UnsupportedOperationException.class, () -> putEntry(requestNestedMap, "extra", "x"));
        assertThrows(UnsupportedOperationException.class, () -> addItem(requestNestedList, "x"));
    }

    @Test
    void afterContextPreservesNullsInsideNestedMapAndList() {
        List<Object> nestedList = new ArrayList<>();
        nestedList.add(null);
        nestedList.add("second");
        Map<String, Object> nestedMap = new LinkedHashMap<>();
        nestedMap.put("nullable", null);
        nestedMap.put("items", nestedList);
        Map<String, Object> mutableInput = new LinkedHashMap<>();
        mutableInput.put("nested", nestedMap);

        AfterToolHookContext context = new AfterToolHookContext(
            new ToolUseRequest("toolu_test", "demo-tool", mutableInput, "msg_parent"),
            tool(),
            mutableInput,
            toolContext(),
            result("original")
        );

        Map<?, ?> contextNestedMap = (Map<?, ?>) context.input().get("nested");
        List<?> contextNestedList = (List<?>) contextNestedMap.get("items");
        Map<?, ?> requestNestedMap = (Map<?, ?>) context.request().input().get("nested");
        List<?> requestNestedList = (List<?>) requestNestedMap.get("items");

        assertTrue(contextNestedMap.containsKey("nullable"));
        assertNull(contextNestedMap.get("nullable"));
        assertEquals(Arrays.asList(null, "second"), contextNestedList);
        assertTrue(requestNestedMap.containsKey("nullable"));
        assertNull(requestNestedMap.get("nullable"));
        assertEquals(Arrays.asList(null, "second"), requestNestedList);
        assertThrows(UnsupportedOperationException.class, () -> putEntry(contextNestedMap, "extra", "x"));
        assertThrows(UnsupportedOperationException.class, () -> addItem(contextNestedList, "x"));
        assertThrows(UnsupportedOperationException.class, () -> putEntry(requestNestedMap, "extra", "x"));
        assertThrows(UnsupportedOperationException.class, () -> addItem(requestNestedList, "x"));
    }

    @Test
    void afterContextPreservesNullsInsideNestedSet() {
        Set<Object> nestedSet = new LinkedHashSet<>();
        nestedSet.add(null);
        nestedSet.add("value");
        Map<String, Object> mutableInput = new LinkedHashMap<>();
        mutableInput.put("set", nestedSet);

        AfterToolHookContext context = new AfterToolHookContext(
            new ToolUseRequest("toolu_test", "demo-tool", mutableInput, "msg_parent"),
            tool(),
            mutableInput,
            toolContext(),
            result("original")
        );

        Set<?> contextSet = (Set<?>) context.input().get("set");
        Set<?> requestSet = (Set<?>) context.request().input().get("set");

        nestedSet.add("late-value");

        assertTrue(contextSet.contains(null));
        assertTrue(contextSet.contains("value"));
        assertFalse(contextSet.contains("late-value"));
        assertTrue(requestSet.contains(null));
        assertTrue(requestSet.contains("value"));
        assertFalse(requestSet.contains("late-value"));
        assertNotSame(nestedSet, contextSet);
        assertThrows(UnsupportedOperationException.class, () -> addSetItem(contextSet, "x"));
        assertThrows(UnsupportedOperationException.class, () -> addSetItem(requestSet, "x"));
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

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void putEntry(Map<?, ?> map, Object key, Object value) {
        ((Map) map).put(key, value);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void addItem(List<?> list, Object value) {
        ((List) list).add(value);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void addSetItem(Set<?> set, Object value) {
        ((Set) set).add(value);
    }

    private Tool<String, String> tool() {
        return new DemoTool();
    }

    private ToolResult<String> result(String output) {
        return new ToolResult<>(output, false, List.of(), Optional.empty());
    }

    private static final class CapturingEventBus implements EventBus {
        private final List<AgentEvent> events = new ArrayList<>();

        @Override
        public void publish(AgentEvent event) {
            events.add(event);
        }

        @Override
        public EventSubscription subscribe(EventFilter filter, EventConsumer consumer) {
            return () -> {
            };
        }
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
