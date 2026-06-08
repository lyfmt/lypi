package cn.lypi.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.common.ToolProgressKind;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.EventConsumer;
import cn.lypi.contracts.event.EventFilter;
import cn.lypi.contracts.event.EventSubscription;
import cn.lypi.contracts.event.PermissionDecisionEvent;
import cn.lypi.contracts.event.PermissionRequestEvent;
import cn.lypi.contracts.event.ToolEndEvent;
import cn.lypi.contracts.event.ToolProgressEvent;
import cn.lypi.contracts.event.ToolStartEvent;
import cn.lypi.contracts.runtime.SecurityRuntimePort;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionResponse;
import cn.lypi.contracts.tool.InterruptBehavior;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolExecutionStatus;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
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
    void defaultGateDeniesWhenToolPermissionRequiresAsk() {
        DefaultToolRuntime runtime = new DefaultToolRuntime(allowAllSecurity());
        runtime.register(TestTools.permission("write", PermissionBehavior.ASK));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "write", Map.of(), "msg_1")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertTrue(result.isError());
        assertTrue(result.newMessages().getFirst().content().getFirst().text().contains("权限请求未获允许"));
    }

    @Test
    void permissionGateAllowContinuesAfterToolAskDecision() {
        AtomicReference<PermissionDecision> requestedDecision = new AtomicReference<>();
        PermissionGate gate = (request, tool, context, decision) -> {
            requestedDecision.set(decision);
            return PermissionGateResult.allow();
        };
        DefaultToolRuntime runtime = runtimeWithGate(gate, allowAllSecurity());
        runtime.register(TestTools.permission("write", PermissionBehavior.ASK));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "write", Map.of("text", "done"), "msg_1")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertFalse(result.isError());
        assertEquals(PermissionBehavior.ASK, requestedDecision.get().behavior());
        assertEquals("done", result.newMessages().getFirst().content().getFirst().text());
    }

    @Test
    void permissionGateAllowContinuesAfterSecurityAskDecision() {
        AtomicReference<PermissionDecision> requestedDecision = new AtomicReference<>();
        SecurityRuntimePort security = (request, context) -> TestTools.decision(PermissionBehavior.ASK, "security ask");
        PermissionGate gate = (request, tool, context, decision) -> {
            requestedDecision.set(decision);
            return PermissionGateResult.allow();
        };
        DefaultToolRuntime runtime = runtimeWithGate(gate, security);
        runtime.register(TestTools.echo("bash", List.of(), false, false, true));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "bash", Map.of("text", "done"), "msg_1")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertFalse(result.isError());
        assertEquals(PermissionBehavior.ASK, requestedDecision.get().behavior());
        assertEquals("security ask", requestedDecision.get().message());
        assertEquals("done", result.newMessages().getFirst().content().getFirst().text());
    }

    @Test
    void permissionGateDenyPreventsToolExecutionForToolAskDecision() {
        AtomicInteger executeCalls = new AtomicInteger();
        PermissionGate gate = (request, tool, context, decision) -> PermissionGateResult.deny("user denied");
        DefaultToolRuntime runtime = runtimeWithGate(gate, allowAllSecurity());
        runtime.register(TestTools.permissionCountingTool("write", PermissionBehavior.ASK, executeCalls));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "write", Map.of("text", "ignored"), "msg_1")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertTrue(result.isError());
        assertEquals(0, executeCalls.get());
        assertTrue(result.newMessages().getFirst().content().getFirst().text().contains("user denied"));
    }

    @Test
    void securityDenyShortCircuitsBeforeToolAskPermissionGate() {
        AtomicInteger gateCalls = new AtomicInteger();
        SecurityRuntimePort security = (request, context) -> TestTools.decision(PermissionBehavior.DENY, "hard deny");
        PermissionGate gate = (request, tool, context, decision) -> {
            gateCalls.incrementAndGet();
            return PermissionGateResult.allow();
        };
        DefaultToolRuntime runtime = runtimeWithGate(gate, security);
        runtime.register(TestTools.permission("edit", PermissionBehavior.ASK));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "edit", Map.of("path", ".git/config"), "msg_1")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertTrue(result.isError());
        assertEquals(0, gateCalls.get());
        assertTrue(result.newMessages().getFirst().content().getFirst().text().contains("hard deny"));
    }

    @Test
    void permissionGateDenyReturnsToolErrorForSecurityAskDecision() {
        SecurityRuntimePort security = (request, context) -> TestTools.decision(PermissionBehavior.ASK, "security ask");
        PermissionGate gate = (request, tool, context, decision) -> PermissionGateResult.deny("user denied");
        DefaultToolRuntime runtime = runtimeWithGate(gate, security);
        runtime.register(TestTools.echo("bash", List.of(), false, false, true));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "bash", Map.of("text", "nope"), "msg_1")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertTrue(result.isError());
        assertTrue(result.newMessages().getFirst().content().getFirst().text().contains("user denied"));
    }

    @Test
    void permissionGateAbortReturnsInterruptedToolError() {
        PermissionGate gate = (request, tool, context, decision) -> PermissionGateResult.abort("user aborted");
        DefaultToolRuntime runtime = runtimeWithGate(gate, allowAllSecurity());
        runtime.register(TestTools.permission("write", PermissionBehavior.ASK));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "write", Map.of("text", "ignored"), "msg_1")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertTrue(result.isError());
        assertTrue(result.newMessages().getFirst().content().getFirst().text().contains("user aborted"));
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
    void exposesSameCwdAsToolContextFactory() {
        Path cwd = Path.of("/workspace/project").toAbsolutePath().normalize();
        DefaultToolRuntime runtime = new DefaultToolRuntime(
            new DefaultToolRegistry(),
            new ToolSchemaValidator(),
            new ToolExecutionPlanner(),
            new ToolResultBudgeter(),
            new ToolRuntimeContextFactory(ToolRuntimeOptions.builder().cwd(cwd).build()),
            ToolExecutionInterceptors.noop(),
            allowAllSecurity()
        );

        assertEquals(cwd, runtime.cwd());
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
    void publishesToolStartProgressAndEndAroundExecution() {
        RecordingEventBus events = new RecordingEventBus();
        DefaultToolRuntime runtime = runtimeWithEvents(events, allowAllSecurity());
        runtime.register(TestTools.progressEcho("bash", "executor progress"));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "bash", Map.of("text", "done"), "msg_1")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertFalse(result.isError());
        assertEquals(3, events.events.size());
        ToolStartEvent start = assertInstanceOf(ToolStartEvent.class, events.events.get(0));
        assertEquals("ses_1", start.sessionId());
        assertEquals("toolu_1", start.toolUseId());
        assertEquals("msg_1", start.parentMessageId());
        assertEquals("turn_1", start.turnId());
        assertEquals("bash", start.toolName());
        assertEquals("Bash", start.displayTitle());
        assertEquals("bash {text=done}", start.inputSummary());
        assertEquals(start.startedAt(), start.timestamp());

        ToolProgressEvent progress = assertInstanceOf(ToolProgressEvent.class, events.events.get(1));
        assertEquals("ses_1", progress.sessionId());
        assertEquals("toolu_1", progress.toolUseId());
        assertEquals(ToolProgressKind.STATUS, progress.progress().kind());
        assertEquals("executor progress", progress.progress().title());

        ToolEndEvent end = assertInstanceOf(ToolEndEvent.class, events.events.get(2));
        assertEquals("ses_1", end.sessionId());
        assertEquals("toolu_1", end.toolUseId());
        assertEquals(ToolExecutionStatus.SUCCEEDED, end.status());
        assertEquals("bash succeeded", end.resultSummary().title());
        assertEquals("done", end.resultSummary().summary());
        assertFalse(end.resultSummary().error());
        assertEquals(4L, end.resultSummary().outputBytes());
        assertEquals(null, end.resultRef());
        assertEquals(end.endedAt(), end.timestamp());
        assertTrue(end.durationMillis() >= 0);
    }

    @Test
    void publicConstructorPublishesProgressWithoutToolLifecycleEventsThroughEventBus() {
        RecordingEventBus events = new RecordingEventBus();
        DefaultToolRuntime runtime = new DefaultToolRuntime(
            ToolRuntimeOptions.builder()
                .sessionId("ses_public")
                .metadata(Map.of("turnId", "turn_public"))
                .build(),
            allowAllSecurity(),
            PermissionGate.denying(),
            events
        );
        runtime.register(TestTools.progressEcho("bash", "executor progress"));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_public", "bash", Map.of("text", "done"), "msg_parent")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertFalse(result.isError());
        assertEquals(1, events.events.size());
        ToolProgressEvent progress = assertInstanceOf(ToolProgressEvent.class, events.events.getFirst());
        assertEquals("ses_public", progress.sessionId());
        assertEquals("toolu_public", progress.toolUseId());
        assertEquals(ToolProgressKind.STATUS, progress.progress().kind());
        assertEquals("executor progress", progress.progress().title());
        assertEquals(0, events.events.stream().filter(ToolStartEvent.class::isInstance).count());
        assertEquals(0, events.events.stream().filter(ToolEndEvent.class::isInstance).count());
    }

    @Test
    void publicConstructorPublishesPermissionProtocolEvents() {
        RecordingEventBus events = new RecordingEventBus();
        PermissionGate gate = (request, tool, context, decision) -> PermissionGateResult.allow();
        DefaultToolRuntime runtime = new DefaultToolRuntime(
            ToolRuntimeOptions.builder()
                .sessionId("ses_public")
                .metadata(Map.of("turnId", "turn_public"))
                .build(),
            allowAllSecurity(),
            gate,
            events
        );
        runtime.register(TestTools.permission("write", PermissionBehavior.ASK));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_public", "write", Map.of("text", "done"), "msg_parent")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertFalse(result.isError());
        assertEquals(2, events.events.size());
        PermissionRequestEvent request = assertInstanceOf(PermissionRequestEvent.class, events.events.get(0));
        PermissionDecisionEvent decision = assertInstanceOf(PermissionDecisionEvent.class, events.events.get(1));
        assertEquals("toolu_public", request.toolUseId());
        assertEquals("write", request.toolName());
        assertEquals("allow_once", decision.selectedOptionId());
    }

    @Test
    void publicConstructorUsesStructuredPermissionResponseGateWithoutDoublePublishing() {
        RecordingEventBus events = new RecordingEventBus();
        AtomicInteger responseRequests = new AtomicInteger();
        PermissionResponseGate responseGate = requestEvent -> {
            responseRequests.incrementAndGet();
            return new PermissionResponse(
                requestEvent.sessionId(),
                requestEvent.requestId(),
                "allow_once",
                false,
                requestEvent.timestamp()
            );
        };
        DefaultToolRuntime runtime = new DefaultToolRuntime(
            ToolRuntimeOptions.builder()
                .sessionId("ses_public")
                .metadata(Map.of("turnId", "turn_public"))
                .build(),
            allowAllSecurity(),
            responseGate,
            events
        );
        runtime.register(TestTools.permission("write", PermissionBehavior.ASK));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_public", "write", Map.of("text", "done"), "msg_parent")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertFalse(result.isError());
        assertEquals(1, responseRequests.get());
        assertEquals(1, events.events.stream().filter(PermissionRequestEvent.class::isInstance).count());
        assertEquals(1, events.events.stream().filter(PermissionDecisionEvent.class::isInstance).count());
        assertEquals(0, events.events.stream().filter(ToolStartEvent.class::isInstance).count());
        assertEquals(0, events.events.stream().filter(ToolEndEvent.class::isInstance).count());
    }

    @Test
    void asksPermissionOnceWhenToolAndSecurityBothRequireConfirmation() {
        RecordingEventBus events = new RecordingEventBus();
        AtomicInteger responseRequests = new AtomicInteger();
        SecurityRuntimePort security = (request, context) -> TestTools.decision(PermissionBehavior.ASK, "security ask");
        PermissionResponseGate responseGate = requestEvent -> {
            responseRequests.incrementAndGet();
            return new PermissionResponse(
                requestEvent.sessionId(),
                requestEvent.requestId(),
                "allow_once",
                false,
                requestEvent.timestamp()
            );
        };
        DefaultToolRuntime runtime = new DefaultToolRuntime(
            ToolRuntimeOptions.builder()
                .sessionId("ses_public")
                .metadata(Map.of("turnId", "turn_public"))
                .build(),
            security,
            responseGate,
            events
        );
        runtime.register(TestTools.permission("write", PermissionBehavior.ASK));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_public", "write", Map.of("text", "done"), "msg_parent")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertFalse(result.isError());
        assertEquals(1, responseRequests.get());
        assertEquals(1, events.events.stream().filter(PermissionRequestEvent.class::isInstance).count());
        assertEquals(1, events.events.stream().filter(PermissionDecisionEvent.class::isInstance).count());
    }

    @Test
    void publishesEndWithErrorWhenToolThrows() {
        RecordingEventBus events = new RecordingEventBus();
        DefaultToolRuntime runtime = runtimeWithEvents(events, allowAllSecurity());
        runtime.register(TestTools.throwingExecute("throwing"));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "throwing", Map.of(), "msg_1")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertTrue(result.isError());
        assertEquals(2, events.events.size());
        assertInstanceOf(ToolStartEvent.class, events.events.get(0));
        ToolEndEvent end = assertInstanceOf(ToolEndEvent.class, events.events.get(1));
        assertEquals(ToolExecutionStatus.FAILED, end.status());
        assertTrue(end.resultSummary().error());
        assertTrue(end.resultSummary().summary().contains("工具执行失败"));
    }

    @Test
    void publishesEndWithErrorWhenAfterInterceptorThrows() {
        RecordingEventBus events = new RecordingEventBus();
        ToolExecutionInterceptor interceptor = ToolExecutionInterceptor.after((request, tool, context, result) -> {
            throw new IllegalStateException("after boom");
        });
        DefaultToolRuntime runtime = runtimeWithEvents(events, allowAllSecurity(), interceptor);
        runtime.register(TestTools.echo("echo", List.of(), true, true, false));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "echo", Map.of("text", "done"), "msg_1")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertTrue(result.isError());
        assertEquals(2, events.events.size());
        assertInstanceOf(ToolStartEvent.class, events.events.get(0));
        ToolEndEvent end = assertInstanceOf(ToolEndEvent.class, events.events.get(1));
        assertEquals(ToolExecutionStatus.FAILED, end.status());
        assertTrue(end.resultSummary().error());
        assertTrue(end.resultSummary().summary().contains("工具执行失败: after boom"));
        assertEquals(result.newMessages().getFirst().content().getFirst().text(), end.resultSummary().summary());
    }

    @Test
    void publishesEndWithOutputRefForBudgetedLongOutput() {
        RecordingEventBus events = new RecordingEventBus();
        DefaultToolRuntime runtime = new DefaultToolRuntime(
            new DefaultToolRegistry(),
            new ToolSchemaValidator(),
            new ToolExecutionPlanner(),
            new ToolResultBudgeter(),
            new ToolRuntimeContextFactory(ToolRuntimeOptions.builder()
                .sessionId("ses_1")
                .metadata(Map.of("turnId", "turn_1"))
                .build()),
            ToolExecutionInterceptors.noop(),
            allowAllSecurity(),
            PermissionGate.denying(),
            ToolExecutionEventPublisher.eventBus(events)
        );
        runtime.register(smallBudgetEcho("bash", 12));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "bash", Map.of("text", "0123456789abcdef"), "msg_1")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertFalse(result.isError());
        ToolEndEvent end = assertInstanceOf(ToolEndEvent.class, events.events.get(1));
        assertEquals(ToolExecutionStatus.SUCCEEDED, end.status());
        assertEquals(16L, end.resultSummary().outputBytes());
        assertEquals("toolout_ses_1_toolu_1", end.resultRef().refId());
        assertEquals("ses_1", end.resultRef().sessionId());
        assertEquals("toolu_1", end.resultRef().toolUseId());
        assertEquals("pending", end.resultRef().storageKind());
        assertEquals("", end.resultRef().location());
        assertEquals(16L, end.resultRef().byteLength());
        assertTrue(end.resultRef().contentHash().startsWith("sha256:"));
        assertEquals("0123456789ab", end.resultRef().metadata().get("preview"));
        assertEquals("budgeted", end.resultRef().metadata().get("truncationReason"));
        assertFalse(end.resultRef().metadata().containsKey("replacementPath"));
        assertFalse(end.resultRef().metadata().containsKey("replacementPreview"));
    }

    @Test
    void doesNotPublishToolExecutionEventsWhenPermissionDeniedBeforeExecution() {
        RecordingEventBus events = new RecordingEventBus();
        SecurityRuntimePort security = (request, context) -> TestTools.decision(PermissionBehavior.DENY, "hard deny");
        DefaultToolRuntime runtime = runtimeWithEvents(events, security);
        runtime.register(TestTools.progressEcho("bash", "executor progress"));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "bash", Map.of("text", "done"), "msg_1")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertTrue(result.isError());
        assertEquals(0, events.events.size());
    }

    @Test
    void publishesProgressForParallelToolsWithoutChangingResultOrder() {
        RecordingEventBus events = new RecordingEventBus();
        DefaultToolRuntime runtime = runtimeWithEvents(events, allowAllSecurity());
        runtime.register(TestTools.progressEcho("first", "first progress"));
        runtime.register(TestTools.progressEcho("second", "second progress"));

        List<ToolResult<?>> results = runtime.execute(List.of(
            new ToolUseRequest("toolu_1", "first", Map.of("text", "one"), "msg_1"),
            new ToolUseRequest("toolu_2", "second", Map.of("text", "two"), "msg_1")
        ), TestTools.context(PermissionMode.DEFAULT_EXECUTE));

        assertEquals("one", results.get(0).newMessages().getFirst().content().getFirst().text());
        assertEquals("two", results.get(1).newMessages().getFirst().content().getFirst().text());
        assertEquals(6, events.events.size());
        assertEquals(2, events.events.stream().filter(ToolStartEvent.class::isInstance).count());
        assertEquals(2, events.events.stream().filter(ToolProgressEvent.class::isInstance).count());
        assertEquals(2, events.events.stream().filter(ToolEndEvent.class::isInstance).count());
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
        ToolResultContentBlock block = (ToolResultContentBlock) result.newMessages().getFirst().content().getFirst();
        assertEquals(ToolExecutionStatus.CANCELLED.name(), block.metadata().get("status"));
    }

    @Test
    void publishesCancelledEndWhenAbortSignalSkipsToolExecution() {
        RecordingEventBus events = new RecordingEventBus();
        AbortSignal signal = () -> true;
        DefaultToolRuntime runtime = new DefaultToolRuntime(
            new DefaultToolRegistry(),
            new ToolSchemaValidator(),
            new ToolExecutionPlanner(),
            new ToolResultBudgeter(),
            new ToolRuntimeContextFactory(ToolRuntimeOptions.builder()
                .sessionId("ses_1")
                .metadata(Map.of("abortSignal", signal, "turnId", "turn_1"))
                .build()),
            ToolExecutionInterceptors.noop(),
            allowAllSecurity(),
            PermissionGate.denying(),
            ToolExecutionEventPublisher.eventBus(events)
        );
        runtime.register(TestTools.echo("echo", List.of(), true, true, false));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "echo", Map.of("text", "hello"), "msg_1")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertTrue(result.isError());
        ToolResultContentBlock block = (ToolResultContentBlock) result.newMessages().getFirst().content().getFirst();
        assertEquals(ToolExecutionStatus.CANCELLED.name(), block.metadata().get("status"));
        assertEquals(2, events.events.size());
        assertInstanceOf(ToolStartEvent.class, events.events.get(0));
        ToolEndEvent end = assertInstanceOf(ToolEndEvent.class, events.events.get(1));
        assertEquals(ToolExecutionStatus.CANCELLED, end.status());
        assertTrue(end.resultSummary().error());
    }

    @Test
    void publishesCancelledEndWhenPermissionGateAborts() {
        RecordingEventBus events = new RecordingEventBus();
        PermissionGate gate = (request, tool, context, decision) -> PermissionGateResult.abort("user aborted");
        DefaultToolRuntime runtime = new DefaultToolRuntime(
            new DefaultToolRegistry(),
            new ToolSchemaValidator(),
            new ToolExecutionPlanner(),
            new ToolResultBudgeter(),
            new ToolRuntimeContextFactory(ToolRuntimeOptions.builder()
                .sessionId("ses_1")
                .metadata(Map.of("turnId", "turn_1"))
                .build()),
            ToolExecutionInterceptors.noop(),
            allowAllSecurity(),
            gate,
            ToolExecutionEventPublisher.eventBus(events)
        );
        runtime.register(TestTools.permission("write", PermissionBehavior.ASK));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "write", Map.of("text", "ignored"), "msg_1")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertTrue(result.isError());
        ToolResultContentBlock block = (ToolResultContentBlock) result.newMessages().getFirst().content().getFirst();
        assertEquals(ToolExecutionStatus.CANCELLED.name(), block.metadata().get("status"));
        assertEquals(2, events.events.size());
        assertInstanceOf(ToolStartEvent.class, events.events.get(0));
        ToolEndEvent end = assertInstanceOf(ToolEndEvent.class, events.events.get(1));
        assertEquals(ToolExecutionStatus.CANCELLED, end.status());
        assertTrue(end.resultSummary().summary().contains("user aborted"));
    }

    @Test
    void abortedParallelBatchSkipsCancelToolsButWaitsForBlockTools() {
        AbortSignal signal = () -> true;
        DefaultToolRuntime runtime = new DefaultToolRuntime(
            ToolRuntimeOptions.builder().metadata(Map.of("abortSignal", signal)).build(),
            allowAllSecurity()
        );
        AtomicInteger cancelCalls = new AtomicInteger();
        AtomicInteger blockCalls = new AtomicInteger();
        runtime.register(TestTools.countingTool("cancel", InterruptBehavior.CANCEL, cancelCalls));
        runtime.register(TestTools.countingTool("block", InterruptBehavior.BLOCK, blockCalls));

        List<ToolResult<?>> results = runtime.execute(List.of(
            new ToolUseRequest("toolu_1", "cancel", Map.of("text", "cancel"), "msg_1"),
            new ToolUseRequest("toolu_2", "block", Map.of("text", "block"), "msg_1")
        ), TestTools.context(PermissionMode.DEFAULT_EXECUTE));

        assertEquals(0, cancelCalls.get());
        assertEquals(1, blockCalls.get());
        assertTrue(results.get(0).isError());
        assertFalse(results.get(1).isError());
    }

    private SecurityRuntimePort allowAllSecurity() {
        return (request, context) -> TestTools.decision(PermissionBehavior.ALLOW, "allowed");
    }

    private DefaultToolRuntime runtimeWithGate(PermissionGate gate, SecurityRuntimePort security) {
        return new DefaultToolRuntime(
            new DefaultToolRegistry(),
            new ToolSchemaValidator(),
            new ToolExecutionPlanner(),
            new ToolResultBudgeter(),
            new ToolRuntimeContextFactory(ToolRuntimeOptions.defaults()),
            ToolExecutionInterceptors.noop(),
            security,
            gate
        );
    }

    private DefaultToolRuntime runtimeWithEvents(EventBus eventBus, SecurityRuntimePort security) {
        return runtimeWithEvents(eventBus, security, ToolExecutionInterceptors.noop());
    }

    private Tool<Map<String, Object>, String> smallBudgetEcho(String name, int maxResultSize) {
        Tool<Map<String, Object>, String> delegate = TestTools.echo(name, List.of(), true, true, false);
        return new Tool<>() {
            @Override
            public String name() {
                return delegate.name();
            }

            @Override
            public List<String> aliases() {
                return delegate.aliases();
            }

            @Override
            public cn.lypi.contracts.common.JsonSchema inputSchema() {
                return delegate.inputSchema();
            }

            @Override
            public cn.lypi.contracts.common.ValidationResult validateInput(
                Map<String, Object> input,
                cn.lypi.contracts.tool.ToolUseContext context
            ) {
                return delegate.validateInput(input, context);
            }

            @Override
            public cn.lypi.contracts.security.PermissionDecision checkPermissions(
                Map<String, Object> input,
                cn.lypi.contracts.tool.ToolUseContext context
            ) {
                return delegate.checkPermissions(input, context);
            }

            @Override
            public ToolResult<String> execute(
                Map<String, Object> input,
                cn.lypi.contracts.tool.ToolUseContext context,
                cn.lypi.contracts.common.ProgressSink progress
            ) {
                return delegate.execute(input, context, progress);
            }

            @Override
            public InterruptBehavior interruptBehavior() {
                return delegate.interruptBehavior();
            }

            @Override
            public boolean isReadOnly(Map<String, Object> input) {
                return delegate.isReadOnly(input);
            }

            @Override
            public boolean isConcurrencySafe(Map<String, Object> input) {
                return delegate.isConcurrencySafe(input);
            }

            @Override
            public boolean isDestructive(Map<String, Object> input) {
                return delegate.isDestructive(input);
            }

            @Override
            public int maxResultSize() {
                return maxResultSize;
            }

            @Override
            public String renderForUser(Map<String, Object> input) {
                return delegate.renderForUser(input);
            }

            @Override
            public cn.lypi.contracts.context.AgentMessage serializeForContext(String output) {
                return delegate.serializeForContext(output);
            }
        };
    }

    private DefaultToolRuntime runtimeWithEvents(
        EventBus eventBus,
        SecurityRuntimePort security,
        ToolExecutionInterceptor interceptor
    ) {
        return new DefaultToolRuntime(
            new DefaultToolRegistry(),
            new ToolSchemaValidator(),
            new ToolExecutionPlanner(),
            new ToolResultBudgeter(),
            new ToolRuntimeContextFactory(ToolRuntimeOptions.builder()
                .sessionId("ses_1")
                .metadata(Map.of("turnId", "turn_1"))
                .build()),
            interceptor,
            security,
            PermissionGate.denying(),
            ToolExecutionEventPublisher.eventBus(eventBus)
        );
    }

    private static final class RecordingEventBus implements EventBus {
        private final List<AgentEvent> events = new CopyOnWriteArrayList<>();

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
}
