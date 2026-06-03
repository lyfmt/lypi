package cn.lypi.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.runtime.SecurityRuntimePort;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DefaultToolRuntimeTest {
    @Test
    void executesRegisteredToolAndReturnsBudgetedResult() {
        DefaultToolRuntime runtime = new DefaultToolRuntime(allowAllSecurity());
        runtime.register(TestTools.echo("echo", List.of("say"), true, true, false));

        List<ToolResult<?>> results = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "say", Map.of("text", "hello"), "msg_1")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        );

        assertEquals(1, results.size());
        assertFalse(results.getFirst().isError());
        ToolResultContentBlock block = (ToolResultContentBlock) results.getFirst().newMessages().getFirst().content().getFirst();
        assertEquals("hello", block.text());
    }

    @Test
    void returnsErrorResultForUnknownTool() {
        DefaultToolRuntime runtime = new DefaultToolRuntime(allowAllSecurity());

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "missing", Map.of(), "msg_1")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertTrue(result.isError());
    }

    @Test
    void passesCanonicalToolNameToSecurityRuntimeWhenCalledByAlias() {
        AtomicReference<String> securityToolName = new AtomicReference<>();
        SecurityRuntimePort security = (request, context) -> {
            securityToolName.set(request.toolName());
            return TestTools.decision(PermissionBehavior.ALLOW, "allowed");
        };
        DefaultToolRuntime runtime = new DefaultToolRuntime(security);
        runtime.register(TestTools.echo("bash", List.of("sh"), true, true, false));

        runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "sh", Map.of("text", "hello"), "msg_1")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        );

        assertEquals("bash", securityToolName.get());
    }

    @Test
    void deniesWhenToolPermissionRequiresAsk() {
        DefaultToolRuntime runtime = new DefaultToolRuntime(allowAllSecurity());
        runtime.register(TestTools.permission("write", PermissionBehavior.ASK));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "write", Map.of(), "msg_1")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertTrue(result.isError());
        assertTrue(result.newMessages().getFirst().content().getFirst().text().contains("工具权限未允许"));
    }

    @Test
    void runsParallelSafeToolsConcurrentlyButKeepsResultOrder() {
        DefaultToolRuntime runtime = new DefaultToolRuntime(allowAllSecurity());
        TestTools.BlockingEchoTool first = new TestTools.BlockingEchoTool("first", Duration.ofMillis(150), true, true);
        TestTools.BlockingEchoTool second = new TestTools.BlockingEchoTool("second", Duration.ZERO, true, true);
        runtime.register(first);
        runtime.register(second);

        long started = System.nanoTime();
        List<ToolResult<?>> results = runtime.execute(List.of(
            new ToolUseRequest("toolu_1", "first", Map.of("text", "one"), "msg_1"),
            new ToolUseRequest("toolu_2", "second", Map.of("text", "two"), "msg_1")
        ), TestTools.context(PermissionMode.DEFAULT_EXECUTE));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertTrue(elapsedMillis < 300);
        assertEquals("one", results.get(0).newMessages().getFirst().content().getFirst().text());
        assertEquals("two", results.get(1).newMessages().getFirst().content().getFirst().text());
    }

    @Test
    void limitsParallelExecutionByRuntimeOptions() {
        DefaultToolRuntime runtime = new DefaultToolRuntime(
            ToolRuntimeOptions.builder().maxConcurrency(1).build(),
            allowAllSecurity()
        );
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        runtime.register(new TestTools.ObservedConcurrencyTool("first", active, maxActive));
        runtime.register(new TestTools.ObservedConcurrencyTool("second", active, maxActive));

        runtime.execute(List.of(
            new ToolUseRequest("toolu_1", "first", Map.of("text", "one"), "msg_1"),
            new ToolUseRequest("toolu_2", "second", Map.of("text", "two"), "msg_1")
        ), TestTools.context(PermissionMode.DEFAULT_EXECUTE));

        assertEquals(1, maxActive.get());
    }

    @Test
    void preservesDuplicateEquivalentRequests() {
        DefaultToolRuntime runtime = new DefaultToolRuntime(allowAllSecurity());
        runtime.register(TestTools.echo("echo", List.of(), true, true, false));
        ToolUseRequest request = new ToolUseRequest("toolu_1", "echo", Map.of("text", "hello"), "msg_1");

        List<ToolResult<?>> results = runtime.execute(
            List.of(request, request),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        );

        assertEquals(2, results.size());
        assertEquals("hello", results.get(0).newMessages().getFirst().content().getFirst().text());
        assertEquals("hello", results.get(1).newMessages().getFirst().content().getFirst().text());
    }

    @Test
    void beforeInterceptorBlocksExecution() {
        ToolExecutionInterceptor interceptor = ToolExecutionInterceptor.before((request, tool, context) ->
            ToolExecutionInterceptor.BeforeResult.block("blocked")
        );
        DefaultToolRuntime runtime = new DefaultToolRuntime(
            new DefaultToolRegistry(),
            new ToolSchemaValidator(),
            new ToolExecutionPlanner(),
            new ToolResultBudgeter(),
            new ToolRuntimeContextFactory(ToolRuntimeOptions.defaults()),
            interceptor,
            allowAllSecurity()
        );
        runtime.register(TestTools.echo("echo", List.of(), true, true, false));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "echo", Map.of("text", "hello"), "msg_1")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertTrue(result.isError());
        assertTrue(result.newMessages().getFirst().content().getFirst().text().contains("blocked"));
    }

    @Test
    void afterInterceptorReceivesNormalizedErrorWhenToolThrows() {
        AtomicInteger afterCalls = new AtomicInteger();
        ToolExecutionInterceptor interceptor = ToolExecutionInterceptor.after((request, tool, context, result) -> {
            afterCalls.incrementAndGet();
            assertTrue(result.isError());
            return TestTools.result(request.toolUseId(), "after saw failure", true);
        });
        DefaultToolRuntime runtime = new DefaultToolRuntime(
            new DefaultToolRegistry(),
            new ToolSchemaValidator(),
            new ToolExecutionPlanner(),
            new ToolResultBudgeter(),
            new ToolRuntimeContextFactory(ToolRuntimeOptions.defaults()),
            interceptor,
            allowAllSecurity()
        );
        runtime.register(TestTools.throwingExecute("throwing"));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "throwing", Map.of(), "msg_1")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertEquals(1, afterCalls.get());
        assertTrue(result.isError());
        assertEquals("after saw failure", result.newMessages().getFirst().content().getFirst().text());
    }

    @Test
    void abortSignalStopsBeforeToolExecution() {
        AbortSignal signal = () -> true;
        DefaultToolRuntime runtime = new DefaultToolRuntime(
            ToolRuntimeOptions.builder().metadata(Map.of("abortSignal", signal)).build(),
            allowAllSecurity()
        );
        runtime.register(TestTools.echo("echo", List.of(), true, true, false));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "echo", Map.of("text", "hello"), "msg_1")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertTrue(result.isError());
        assertTrue(result.newMessages().getFirst().content().getFirst().text().contains("工具调用已中止"));
    }

    private SecurityRuntimePort allowAllSecurity() {
        return (request, context) -> TestTools.decision(PermissionBehavior.ALLOW, "allowed");
    }
}
