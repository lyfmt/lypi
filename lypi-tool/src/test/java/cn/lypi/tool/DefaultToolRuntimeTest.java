package cn.lypi.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ToolProgressKind;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.AttachmentContentBlock;
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
import cn.lypi.contracts.runtime.ExecutionRequest;
import cn.lypi.contracts.runtime.ExecutionResult;
import cn.lypi.contracts.runtime.Executor;
import cn.lypi.contracts.runtime.NetworkMode;
import cn.lypi.contracts.runtime.SandboxPermissions;
import cn.lypi.contracts.runtime.SecurityRuntimePort;
import cn.lypi.contracts.runtime.SandboxRuntimePolicy;
import cn.lypi.contracts.runtime.ToolRuntimeInvocation;
import cn.lypi.contracts.security.AdditionalPermissionProfile;
import cn.lypi.contracts.security.BashRiskAnalysis;
import cn.lypi.contracts.security.BashRiskLevel;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.FileSystemAccessMode;
import cn.lypi.contracts.security.FileSystemPath;
import cn.lypi.contracts.security.FileSystemPermissionEntry;
import cn.lypi.contracts.security.FileSystemPermissionPolicy;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionGrantScope;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionResponse;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.security.PermissionRuleSource;
import cn.lypi.contracts.security.PermissionRuleValue;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.security.RequestPermissionProfile;
import cn.lypi.contracts.security.RequestPermissionsResponse;
import cn.lypi.contracts.tool.InterruptBehavior;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolExecutionStatus;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseRequest;
import cn.lypi.tool.builtin.BashTool;
import cn.lypi.tool.builtin.ReadTool;
import cn.lypi.tool.builtin.RequestPermissionsTool;
import cn.lypi.tool.builtin.WriteTool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultToolRuntimeTest {
    @TempDir
    Path tempDir;

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
    void preservesReadImageAttachmentThroughRuntime() throws Exception {
        Files.write(tempDir.resolve("image.png"), png1x1());
        DefaultToolRuntime runtime = new DefaultToolRuntime(
            ToolRuntimeOptions.builder().cwd(tempDir).build(),
            allowAllSecurity(),
            PermissionGate.denying(),
            null
        );
        runtime.register(new ReadTool());

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "read", Map.of("path", "image.png"), "msg_1")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertFalse(result.isError());
        assertTrue(result.output().toString().contains("Read image file [image/png]"));
        assertFalse(result.output().toString().contains("base64"));
        assertEquals(2, result.newMessages().getFirst().content().size());
        assertInstanceOf(ToolResultContentBlock.class, result.newMessages().getFirst().content().get(0));
        assertInstanceOf(AttachmentContentBlock.class, result.newMessages().getFirst().content().get(1));
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
    void publishesLifecycleForUnknownTool() {
        RecordingEventBus events = new RecordingEventBus();
        DefaultToolRuntime runtime = runtimeWithEvents(events, allowAllSecurity());

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "missing", Map.of("path", "none"), "msg_1")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertTrue(result.isError());
        List<AgentEvent> lifecycle = lifecycleEvents(events);
        assertEquals(2, lifecycle.size());
        ToolStartEvent start = assertInstanceOf(ToolStartEvent.class, lifecycle.get(0));
        assertEquals("ses_1", start.sessionId());
        assertEquals("toolu_1", start.toolUseId());
        assertEquals("msg_1", start.parentMessageId());
        assertEquals("turn_1", start.turnId());
        assertEquals("missing", start.toolName());
        assertEquals("missing {path=none}", start.inputSummary());
        assertEquals("none", start.inputMetadata().get("path"));
        ToolEndEvent end = assertInstanceOf(ToolEndEvent.class, lifecycle.get(1));
        assertEquals("toolu_1", end.toolUseId());
        assertEquals(ToolExecutionStatus.FAILED, end.status());
        assertTrue(end.resultSummary().error());
        assertEquals("missing", end.metadata().get("toolName"));
        assertTrue(end.durationMillis() >= 0);
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
    void publishesCanonicalLifecycleWithOriginalToolNameMetadataWhenCalledByAlias() {
        RecordingEventBus events = new RecordingEventBus();
        DefaultToolRuntime runtime = runtimeWithEvents(events, allowAllSecurity());
        runtime.register(TestTools.echo("bash", List.of("sh"), true, true, false));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "sh", Map.of("text", "hello"), "msg_1")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertFalse(result.isError());
        ToolStartEvent start = assertInstanceOf(ToolStartEvent.class, events.events.get(0));
        assertEquals("bash", start.toolName());
        assertEquals("sh", start.inputMetadata().get("originalToolName"));
        ToolEndEvent end = assertInstanceOf(ToolEndEvent.class, events.events.get(1));
        assertEquals("bash", end.metadata().get("toolName"));
        assertEquals("sh", end.metadata().get("originalToolName"));
        assertEquals("bash", end.resultSummary().metadata().get("toolName"));
        assertEquals("sh", end.resultSummary().metadata().get("originalToolName"));
    }

    @Test
    void invocationOverridesStaticOptionsForLifecycleOwnership() {
        RecordingEventBus events = new RecordingEventBus();
        DefaultToolRuntime runtime = runtimeWithEvents(events, allowAllSecurity());
        runtime.register(TestTools.echo("bash", List.of(), true, true, false));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "bash", Map.of("text", "hello"), "msg_1")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE),
            new ToolRuntimeInvocation("session-dynamic", "turn-dynamic")
        ).getFirst();

        assertFalse(result.isError());
        ToolStartEvent start = assertInstanceOf(ToolStartEvent.class, events.events.get(0));
        assertEquals("session-dynamic", start.sessionId());
        assertEquals("turn-dynamic", start.turnId());
        ToolEndEvent end = assertInstanceOf(ToolEndEvent.class, events.events.get(1));
        assertEquals("session-dynamic", end.sessionId());
    }

    @Test
    void publishesLifecycleWhenInputContainsNullValue() {
        RecordingEventBus events = new RecordingEventBus();
        DefaultToolRuntime runtime = runtimeWithEvents(events, allowAllSecurity());
        runtime.register(TestTools.requiredTextEcho("schema"));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("text", null);

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "schema", input, "msg_1")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertTrue(result.isError());
        ToolStartEvent start = assertInstanceOf(ToolStartEvent.class, events.events.get(0));
        assertNull(start.inputMetadata().get("text"));
        ToolEndEvent end = assertInstanceOf(ToolEndEvent.class, events.events.get(1));
        assertEquals(ToolExecutionStatus.FAILED, end.status());
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
    void sandboxAutoAllowBashToolPermissionDoesNotInvokeDenyingGate() {
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(0, "done", "", false, Optional.empty()));
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/usr")),
            List.of(),
            List.of(tempDir),
            List.of(),
            NetworkMode.DISABLED,
            true,
            true
        );
        DefaultToolRuntime runtime = new DefaultToolRuntime(
            ToolRuntimeOptions.builder().cwd(tempDir).build(),
            allowAllSecurity(),
            PermissionGate.denying(),
            null
        );
        runtime.register(new BashTool(executor, (workspace, cwd) -> policy));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "bash", Map.of("command", "echo done"), "msg_1")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertFalse(result.isError());
        assertEquals(1, executor.calls.get());
        assertEquals(List.of("bash", "-lc", "echo done"), executor.request.get().command());
        assertTrue(result.newMessages().getFirst().content().getFirst().text().contains("stdout:\ndone"));
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
        runtime.register(TestTools.echo("write", List.of(), false, false, true));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "write", Map.of("text", "done"), "msg_1")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertFalse(result.isError());
        assertEquals(PermissionBehavior.ASK, requestedDecision.get().behavior());
        assertEquals("security ask", requestedDecision.get().message());
        assertEquals("done", result.newMessages().getFirst().content().getFirst().text());
    }

    @Test
    void allowAndRememberAppendsPermissionAmendmentAndUpdatesRuntimeMemory() {
        AtomicInteger gateCalls = new AtomicInteger();
        PermissionGate gate = (request, tool, context, decision) -> {
            gateCalls.incrementAndGet();
            return PermissionGateResult.allow(decision.suggestedUpdate());
        };
        FilePermissionAmendmentStore amendmentStore = new FilePermissionAmendmentStore(tempDir);
        DefaultToolRuntime runtime = new DefaultToolRuntime(
            new DefaultToolRegistry(),
            new ToolSchemaValidator(),
            new ToolExecutionPlanner(),
            new ToolResultBudgeter(),
            new ToolRuntimeContextFactory(ToolRuntimeOptions.builder().cwd(tempDir).build()),
            ToolExecutionInterceptors.noop(),
            prefixMemorySecurity(),
            gate,
            ToolExecutionEventPublisher.noop(),
            amendmentStore
        );
        AtomicInteger executeCalls = new AtomicInteger();
        runtime.register(TestTools.permissionCountingTool("bash", PermissionBehavior.ALLOW, executeCalls));

        ToolUseRequest first = new ToolUseRequest(
            "toolu_1",
            "bash",
            Map.of("command", "go test ./...", "text", "first", "prefix_rule", List.of("go", "test")),
            "msg_1"
        );
        ToolUseRequest second = new ToolUseRequest(
            "toolu_2",
            "bash",
            Map.of("command", "go test ./...", "text", "second", "prefix_rule", List.of("go", "test")),
            "msg_1"
        );

        ToolResult<?> firstResult = runtime.execute(List.of(first), TestTools.context(PermissionMode.DEFAULT_EXECUTE)).getFirst();
        ToolResult<?> secondResult = runtime.execute(List.of(second), TestTools.context(PermissionMode.DEFAULT_EXECUTE)).getFirst();

        assertFalse(firstResult.isError());
        assertFalse(secondResult.isError());
        assertEquals(1, gateCalls.get());
        assertEquals(2, executeCalls.get());
        assertEquals(List.of(prefixUpdate("go test")), amendmentStore.readPermissionUpdates(PermissionGrantScope.SESSION));
        assertFalse(Files.exists(tempDir.resolve("rules/default.rules")));
    }

    @Test
    void rememberedPermissionAmendmentDoesNotLeakAcrossSessions() {
        AtomicInteger gateCalls = new AtomicInteger();
        PermissionGate gate = (request, tool, context, decision) -> {
            gateCalls.incrementAndGet();
            if ("ses_1".equals(context.sessionId())) {
                return PermissionGateResult.allow(decision.suggestedUpdate());
            }
            return PermissionGateResult.allow();
        };
        FilePermissionAmendmentStore amendmentStore = new FilePermissionAmendmentStore(tempDir);
        DefaultToolRuntime runtime = new DefaultToolRuntime(
            new DefaultToolRegistry(),
            new ToolSchemaValidator(),
            new ToolExecutionPlanner(),
            new ToolResultBudgeter(),
            new ToolRuntimeContextFactory(ToolRuntimeOptions.builder().cwd(tempDir).build()),
            ToolExecutionInterceptors.noop(),
            prefixMemorySecurity(),
            gate,
            ToolExecutionEventPublisher.noop(),
            amendmentStore
        );
        runtime.register(TestTools.permissionCountingTool("bash", PermissionBehavior.ALLOW, new AtomicInteger()));

        ToolUseRequest first = new ToolUseRequest(
            "toolu_1",
            "bash",
            Map.of("command", "go test ./...", "text", "first", "prefix_rule", List.of("go", "test")),
            "msg_1"
        );
        ToolUseRequest second = new ToolUseRequest(
            "toolu_2",
            "bash",
            Map.of("command", "go test ./...", "text", "second", "prefix_rule", List.of("go", "test")),
            "msg_1"
        );

        runtime.execute(
            List.of(first),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE),
            new ToolRuntimeInvocation("ses_1", "turn_1")
        );
        runtime.execute(
            List.of(second),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE),
            new ToolRuntimeInvocation("ses_2", "turn_2")
        );

        assertEquals(2, gateCalls.get());
        assertEquals(List.of(prefixUpdate("go test")), amendmentStore.readPermissionUpdates(PermissionGrantScope.SESSION, "ses_1"));
        assertTrue(amendmentStore.readPermissionUpdates(PermissionGrantScope.SESSION, "ses_2").isEmpty());
    }

    @Test
    void rememberedPrefixAllowSkipsBashToolAskForRepeatedPrefixRuleRequest() {
        AtomicInteger gateCalls = new AtomicInteger();
        PermissionGate gate = (request, tool, context, decision) -> {
            gateCalls.incrementAndGet();
            return PermissionGateResult.deny("should not ask");
        };
        SecurityRuntimePort security = (request, context) -> new PermissionDecision(
            PermissionBehavior.ALLOW,
            PermissionDecisionReason.EXPLICIT_RULE,
            "命中 Bash prefix 规则",
            Optional.empty(),
            Map.of()
        );
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/usr")),
            List.of(),
            List.of(tempDir),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );
        DefaultToolRuntime runtime = new DefaultToolRuntime(
            new DefaultToolRegistry(),
            new ToolSchemaValidator(),
            new ToolExecutionPlanner(),
            new ToolResultBudgeter(),
            new ToolRuntimeContextFactory(ToolRuntimeOptions.builder().cwd(tempDir).build()),
            ToolExecutionInterceptors.noop(),
            security,
            gate,
            ToolExecutionEventPublisher.noop(),
            new FilePermissionUpdateStore(tempDir)
        );
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(0, "done", "", false, Optional.empty()));
        runtime.register(new BashTool(executor, (workspace, cwd) -> policy));

        ToolUseRequest request = new ToolUseRequest(
            "toolu_1",
            "bash",
            Map.of(
                "command", "bash -lc \"mvn -pl lypi-security test\"",
                "text", "done",
                "prefix_rule", List.of("mvn", "-pl"),
                "sandboxPermissions", "useDefault"
            ),
            "msg_1"
        );

        ToolResult<?> result = runtime.execute(List.of(request), TestTools.context(PermissionMode.DEFAULT_EXECUTE)).getFirst();

        assertFalse(result.isError());
        assertEquals(0, gateCalls.get());
        assertEquals(1, executor.calls.get());
    }

    @Test
    void allowOnceDoesNotPersistExecPolicyRule() {
        AtomicInteger gateCalls = new AtomicInteger();
        PermissionGate gate = (request, tool, context, decision) -> {
            gateCalls.incrementAndGet();
            return PermissionGateResult.allow();
        };
        DefaultToolRuntime runtime = new DefaultToolRuntime(
            new DefaultToolRegistry(),
            new ToolSchemaValidator(),
            new ToolExecutionPlanner(),
            new ToolResultBudgeter(),
            new ToolRuntimeContextFactory(ToolRuntimeOptions.builder().cwd(tempDir).build()),
            ToolExecutionInterceptors.noop(),
            prefixMemorySecurity(),
            gate,
            ToolExecutionEventPublisher.noop(),
            new FilePermissionUpdateStore(tempDir)
        );
        runtime.register(TestTools.permissionCountingTool("bash", PermissionBehavior.ALLOW, new AtomicInteger()));

        ToolUseRequest request = new ToolUseRequest(
            "toolu_1",
            "bash",
            Map.of("command", "go test ./...", "text", "once", "prefix_rule", List.of("go", "test")),
            "msg_1"
        );

        ToolResult<?> result = runtime.execute(List.of(request), TestTools.context(PermissionMode.DEFAULT_EXECUTE)).getFirst();

        assertFalse(result.isError());
        assertEquals(1, gateCalls.get());
        assertFalse(Files.exists(tempDir.resolve("rules/default.rules")));
    }

    @Test
    void defaultSandboxBashSkipsUserApprovalAndExecutesInSandboxPath() {
        AtomicInteger gateCalls = new AtomicInteger();
        AtomicInteger permissionCalls = new AtomicInteger();
        AtomicInteger executeCalls = new AtomicInteger();
        SecurityRuntimePort security = (request, context) -> TestTools.decision(PermissionBehavior.ALLOW, "low risk");
        PermissionGate gate = (request, tool, context, decision) -> {
            gateCalls.incrementAndGet();
            return PermissionGateResult.deny("should not ask before sandbox");
        };
        DefaultToolRuntime runtime = runtimeWithGate(gate, security);
        runtime.register(TestTools.permissionAndExecutionCountingTool(
            "bash",
            PermissionBehavior.ASK,
            permissionCalls,
            executeCalls
        ));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "bash", Map.of("text", "sandbox"), "msg_1")),
            TestTools.context(AgentMode.EXECUTE, PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertFalse(result.isError());
        assertEquals("sandbox", result.newMessages().getFirst().content().getFirst().text());
        assertEquals(0, gateCalls.get());
        assertEquals(0, permissionCalls.get());
        assertEquals(1, executeCalls.get());
    }

    @Test
    void defaultSandboxBashNonDangerousRiskAskSkipsUserApprovalAndExecutesInSandboxPath() {
        AtomicInteger gateCalls = new AtomicInteger();
        AtomicInteger executeCalls = new AtomicInteger();
        SecurityRuntimePort security = (request, context) -> bashRiskDecision(BashRiskLevel.HIGH, "git push");
        PermissionGate gate = (request, tool, context, decision) -> {
            gateCalls.incrementAndGet();
            return PermissionGateResult.deny("should not ask before sandbox");
        };
        DefaultToolRuntime runtime = runtimeWithGate(gate, security);
        runtime.register(TestTools.permissionCountingTool("bash", PermissionBehavior.ALLOW, executeCalls));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "bash", Map.of("text", "sandbox"), "msg_1")),
            TestTools.context(AgentMode.EXECUTE, PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertFalse(result.isError());
        assertEquals(0, gateCalls.get());
        assertEquals(1, executeCalls.get());
    }

    @Test
    void defaultSandboxBashRedirectAskRequiresUserApproval() {
        AtomicReference<PermissionDecision> requestedDecision = new AtomicReference<>();
        AtomicInteger executeCalls = new AtomicInteger();
        SecurityRuntimePort security = (request, context) -> bashRiskDecision(
            BashRiskLevel.MEDIUM,
            "echo ok > notes/output.txt",
            List.of(Path.of("notes/output.txt"))
        );
        PermissionGate gate = (request, tool, context, decision) -> {
            requestedDecision.set(decision);
            return PermissionGateResult.allow();
        };
        DefaultToolRuntime runtime = runtimeWithGate(gate, security);
        runtime.register(TestTools.permissionCountingTool("bash", PermissionBehavior.ALLOW, executeCalls));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "bash", Map.of("text", "sandbox"), "msg_1")),
            TestTools.context(AgentMode.EXECUTE, PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertFalse(result.isError());
        assertEquals(PermissionBehavior.ASK, requestedDecision.get().behavior());
        assertEquals(1, executeCalls.get());
    }

    @Test
    void defaultExecuteDeniesDangerousDefaultBashBeforeGateAndExecutor() {
        AtomicInteger gateCalls = new AtomicInteger();
        AtomicInteger executeCalls = new AtomicInteger();
        SecurityRuntimePort security = (request, context) -> bashRiskDecision(BashRiskLevel.DESTRUCTIVE, "rm -f 洗车店.md");
        PermissionGate gate = (request, tool, context, decision) -> {
            gateCalls.incrementAndGet();
            return PermissionGateResult.allow();
        };
        DefaultToolRuntime runtime = runtimeWithGate(gate, security);
        runtime.register(TestTools.permissionCountingTool("bash", PermissionBehavior.ALLOW, executeCalls));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "bash", Map.of("text", "ignored"), "msg_1")),
            TestTools.context(AgentMode.EXECUTE, PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertTrue(result.isError());
        assertSandboxRetryHint(result);
        assertEquals(0, gateCalls.get());
        assertEquals(0, executeCalls.get());
    }

    @Test
    void defaultExecuteDeniesSudoDangerousDefaultBashBeforeGateAndExecutor() {
        AtomicInteger executeCalls = new AtomicInteger();
        SecurityRuntimePort security = (request, context) -> bashRiskDecision(BashRiskLevel.HIGH, "sudo rm -f 洗车店.md");
        DefaultToolRuntime runtime = runtimeWithGate(PermissionGate.denying(), security);
        runtime.register(TestTools.permissionCountingTool("bash", PermissionBehavior.ALLOW, executeCalls));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "bash", Map.of("text", "ignored"), "msg_1")),
            TestTools.context(AgentMode.EXECUTE, PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertTrue(result.isError());
        assertSandboxRetryHint(result);
        assertEquals(0, executeCalls.get());
    }

    @Test
    void defaultExecuteDeniesShellLcDangerousDefaultBashBeforeGateAndExecutor() {
        AtomicInteger executeCalls = new AtomicInteger();
        SecurityRuntimePort security = (request, context) -> bashRiskDecision(
            BashRiskLevel.UNKNOWN,
            "bash -lc \"echo hi && rm -rf target\""
        );
        DefaultToolRuntime runtime = runtimeWithGate(PermissionGate.denying(), security);
        runtime.register(TestTools.permissionCountingTool("bash", PermissionBehavior.ALLOW, executeCalls));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "bash", Map.of("text", "ignored"), "msg_1")),
            TestTools.context(AgentMode.EXECUTE, PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertTrue(result.isError());
        assertSandboxRetryHint(result);
        assertEquals(0, executeCalls.get());
    }

    @Test
    void defaultExecuteDeniesSudoShellLcDangerousDefaultBashBeforeGateAndExecutor() {
        AtomicInteger executeCalls = new AtomicInteger();
        SecurityRuntimePort security = (request, context) -> bashRiskDecision(
            BashRiskLevel.HIGH,
            "sudo bash -lc \"rm -rf target\""
        );
        DefaultToolRuntime runtime = runtimeWithGate(PermissionGate.denying(), security);
        runtime.register(TestTools.permissionCountingTool("bash", PermissionBehavior.ALLOW, executeCalls));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "bash", Map.of("text", "ignored"), "msg_1")),
            TestTools.context(AgentMode.EXECUTE, PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertTrue(result.isError());
        assertSandboxRetryHint(result);
        assertEquals(0, executeCalls.get());
    }

    @Test
    void defaultBashSandboxRiskDoesNotOverrideSecurityDeny() {
        AtomicInteger executeCalls = new AtomicInteger();
        SecurityRuntimePort security = (request, context) -> new PermissionDecision(
            PermissionBehavior.DENY,
            PermissionDecisionReason.PATH_SAFETY,
            "hard security deny",
            Optional.<PermissionUpdate>empty(),
            Map.of("bashRisk", new BashRiskAnalysis(
                "rm -f 洗车店.md",
                List.of("rm -f 洗车店.md"),
                List.of(),
                BashRiskLevel.DESTRUCTIVE,
                List.of("test risk"),
                true
            ))
        );
        DefaultToolRuntime runtime = runtimeWithGate(PermissionGate.denying(), security);
        runtime.register(TestTools.permissionCountingTool("bash", PermissionBehavior.ALLOW, executeCalls));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "bash", Map.of("text", "ignored"), "msg_1")),
            TestTools.context(AgentMode.EXECUTE, PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        String text = result.newMessages().getFirst().content().getFirst().text();
        assertTrue(result.isError());
        assertTrue(text.contains("hard security deny"));
        assertFalse(text.contains("retryWith=sandboxPermissions=requireEscalated"));
        assertEquals(0, executeCalls.get());
    }

    @Test
    void defaultExecuteDoesNotDenyNonCodexDangerousDefaultBash() {
        AtomicInteger gateCalls = new AtomicInteger();
        AtomicInteger executeCalls = new AtomicInteger();
        SecurityRuntimePort security = (request, context) -> bashRiskDecision(BashRiskLevel.DESTRUCTIVE, "rm 洗车店.md");
        PermissionGate gate = (request, tool, context, decision) -> {
            gateCalls.incrementAndGet();
            return PermissionGateResult.deny("should not ask before sandbox");
        };
        DefaultToolRuntime runtime = runtimeWithGate(gate, security);
        runtime.register(TestTools.permissionCountingTool("bash", PermissionBehavior.ALLOW, executeCalls));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "bash", Map.of("text", "sandbox"), "msg_1")),
            TestTools.context(AgentMode.EXECUTE, PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertFalse(result.isError());
        assertEquals(0, gateCalls.get());
        assertEquals(1, executeCalls.get());
    }

    @Test
    void acceptEditsDeniesDefaultBashBeforeGateAndExecutor() {
        AtomicInteger gateCalls = new AtomicInteger();
        AtomicInteger executeCalls = new AtomicInteger();
        PermissionGate gate = (request, tool, context, decision) -> {
            gateCalls.incrementAndGet();
            return PermissionGateResult.allow();
        };
        DefaultToolRuntime runtime = runtimeWithGate(gate, allowAllSecurity());
        runtime.register(TestTools.permissionCountingTool("bash", PermissionBehavior.ALLOW, executeCalls));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "bash", Map.of("text", "ignored"), "msg_1")),
            TestTools.context(AgentMode.EXECUTE, PermissionMode.ACCEPT_EDITS)
        ).getFirst();

        assertTrue(result.isError());
        assertSandboxRetryHint(result);
        assertEquals(0, gateCalls.get());
        assertEquals(0, executeCalls.get());
    }

    @Test
    void bypassDoesNotApplyDefaultBashRiskPolicy() {
        AtomicInteger gateCalls = new AtomicInteger();
        AtomicInteger executeCalls = new AtomicInteger();
        SecurityRuntimePort security = (request, context) -> bashRiskDecision(BashRiskLevel.DESTRUCTIVE, "rm -f 洗车店.md");
        PermissionGate gate = (request, tool, context, decision) -> {
            gateCalls.incrementAndGet();
            return PermissionGateResult.deny("should not ask");
        };
        DefaultToolRuntime runtime = runtimeWithGate(gate, security);
        runtime.register(TestTools.permissionCountingTool("bash", PermissionBehavior.ALLOW, executeCalls));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "bash", Map.of("text", "bypass"), "msg_1")),
            TestTools.context(AgentMode.EXECUTE, PermissionMode.BYPASS)
        ).getFirst();

        assertFalse(result.isError());
        assertEquals(0, gateCalls.get());
        assertEquals(1, executeCalls.get());
    }

    @Test
    void requireEscalatedBashSkipsDefaultBashRiskPolicyAndAsksUser() {
        AtomicReference<PermissionDecision> requestedDecision = new AtomicReference<>();
        AtomicInteger executeCalls = new AtomicInteger();
        SecurityRuntimePort security = (request, context) -> bashRiskDecision(BashRiskLevel.DESTRUCTIVE, "rm -f 洗车店.md");
        PermissionGate gate = (request, tool, context, decision) -> {
            requestedDecision.set(decision);
            return PermissionGateResult.allow();
        };
        DefaultToolRuntime runtime = runtimeWithGate(gate, security);
        runtime.register(TestTools.permissionCountingTool("bash", PermissionBehavior.ALLOW, executeCalls));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "bash", Map.of(
                "text", "host",
                "sandboxPermissions", "requireEscalated",
                "justification", "用户明确要求删除当前目录下的文件。"
            ), "msg_1")),
            TestTools.context(AgentMode.EXECUTE, PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertFalse(result.isError());
        assertEquals(PermissionBehavior.ASK, requestedDecision.get().behavior());
        assertTrue(requestedDecision.get().message().contains("沙箱提权执行"));
        assertTrue(requestedDecision.get().message().contains("用户明确要求删除当前目录下的文件。"));
        assertEquals(1, executeCalls.get());
    }

    @Test
    void planModeAllowsReadOnlyTools() {
        AtomicInteger securityCalls = new AtomicInteger();
        SecurityRuntimePort security = (request, context) -> {
            securityCalls.incrementAndGet();
            return TestTools.decision(PermissionBehavior.ALLOW, "allowed");
        };
        DefaultToolRuntime runtime = new DefaultToolRuntime(security);
        runtime.register(TestTools.echo("read", List.of(), true, true, false));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "read", Map.of("text", "ok"), "msg_1")),
            TestTools.context(AgentMode.PLAN, PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertFalse(result.isError());
        assertEquals(1, securityCalls.get());
        assertEquals("ok", result.newMessages().getFirst().content().getFirst().text());
    }

    @Test
    void planModeRejectsNonReadOnlyToolsBeforePermissionsAndExecution() {
        AtomicInteger securityCalls = new AtomicInteger();
        AtomicInteger gateCalls = new AtomicInteger();
        AtomicInteger toolPermissionCalls = new AtomicInteger();
        AtomicInteger executeCalls = new AtomicInteger();
        SecurityRuntimePort security = (request, context) -> {
            securityCalls.incrementAndGet();
            return TestTools.decision(PermissionBehavior.ALLOW, "allowed");
        };
        PermissionGate gate = (request, tool, context, decision) -> {
            gateCalls.incrementAndGet();
            return PermissionGateResult.allow();
        };
        Tool<Map<String, Object>, String> writeTool = TestTools.permissionAndExecutionCountingTool(
            "write",
            PermissionBehavior.ALLOW,
            toolPermissionCalls,
            executeCalls
        );
        DefaultToolRuntime runtime = runtimeWithGate(gate, security);
        runtime.register(writeTool);

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "write", Map.of("text", "blocked"), "msg_1")),
            TestTools.context(AgentMode.PLAN, PermissionMode.BYPASS)
        ).getFirst();

        assertTrue(result.isError());
        assertTrue(result.newMessages().getFirst().content().getFirst().text().contains("AgentMode.PLAN"));
        assertEquals(0, securityCalls.get());
        assertEquals(0, gateCalls.get());
        assertEquals(0, toolPermissionCalls.get());
        assertEquals(0, executeCalls.get());
    }

    @Test
    void bypassPermissionModeAllowsExplicitSandboxEscalationWithoutPrompt() {
        AtomicInteger gateCalls = new AtomicInteger();
        PermissionGate gate = (request, tool, context, decision) -> {
            gateCalls.incrementAndGet();
            return PermissionGateResult.deny("should not ask");
        };
        DefaultToolRuntime runtime = runtimeWithGate(gate, allowAllSecurity());
        runtime.register(TestTools.echo("bash", List.of(), false, false, true));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "bash", Map.of(
                "text", "done",
                "sandboxPermissions", "requireEscalated",
                "justification", "Need host access."
            ), "msg_1")),
            TestTools.context(AgentMode.EXECUTE, PermissionMode.BYPASS)
        ).getFirst();

        assertFalse(result.isError());
        assertEquals(0, gateCalls.get());
    }

    @Test
    void defaultExecuteAsksForExplicitSandboxEscalationWithUserJustification() {
        AtomicReference<PermissionDecision> requestedDecision = new AtomicReference<>();
        PermissionGate gate = (request, tool, context, decision) -> {
            requestedDecision.set(decision);
            return PermissionGateResult.allow();
        };
        DefaultToolRuntime runtime = runtimeWithGate(gate, allowAllSecurity());
        runtime.register(TestTools.echo("bash", List.of(), false, false, true));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "bash", Map.of(
                "text", "done",
                "sandboxPermissions", "requireEscalated",
                "justification", "Need host access."
            ), "msg_1")),
            TestTools.context(AgentMode.EXECUTE, PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertFalse(result.isError());
        assertEquals(PermissionBehavior.ASK, requestedDecision.get().behavior());
        assertTrue(requestedDecision.get().message().contains("沙箱提权执行"));
        assertTrue(requestedDecision.get().message().contains("Need host access."));
    }

    @Test
    void explicitSandboxEscalationKeepsSecuritySuggestedUpdate() {
        AtomicReference<PermissionDecision> requestedDecision = new AtomicReference<>();
        PermissionGate gate = (request, tool, context, decision) -> {
            requestedDecision.set(decision);
            return PermissionGateResult.allow(decision.suggestedUpdate());
        };
        PermissionUpdate update = prefixUpdate("mvn -pl");
        SecurityRuntimePort security = (request, context) -> new PermissionDecision(
            PermissionBehavior.ASK,
            PermissionDecisionReason.BASH_RISK,
            "默认执行模式下 Bash 写入、网络或远端变更需要用户确认。",
            Optional.of(update),
            Map.of("bashRisk", new BashRiskAnalysis(
                "mvn -pl lypi-security test",
                List.of("mvn -pl lypi-security test"),
                List.of(),
                BashRiskLevel.MEDIUM,
                List.of("包含未登记命令"),
                true
            ))
        );
        DefaultToolRuntime runtime = new DefaultToolRuntime(
            new DefaultToolRegistry(),
            new ToolSchemaValidator(),
            new ToolExecutionPlanner(),
            new ToolResultBudgeter(),
            new ToolRuntimeContextFactory(ToolRuntimeOptions.builder().cwd(tempDir).build()),
            ToolExecutionInterceptors.noop(),
            security,
            gate,
            ToolExecutionEventPublisher.noop(),
            new FilePermissionUpdateStore(tempDir)
        );
        runtime.register(TestTools.echo("bash", List.of(), false, false, true));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "bash", Map.of(
                "text", "done",
                "sandboxPermissions", "requireEscalated",
                "justification", "Need host access."
            ), "msg_1")),
            TestTools.context(AgentMode.EXECUTE, PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertFalse(result.isError());
        assertEquals(PermissionBehavior.ASK, requestedDecision.get().behavior());
        assertTrue(requestedDecision.get().message().contains("沙箱提权执行"));
        assertTrue(requestedDecision.get().suggestedUpdate().isPresent());
        assertEquals(update, requestedDecision.get().suggestedUpdate().orElseThrow());
    }

    @Test
    void acceptEditsAsksForExplicitSandboxEscalation() {
        AtomicReference<PermissionDecision> requestedDecision = new AtomicReference<>();
        PermissionGate gate = (request, tool, context, decision) -> {
            requestedDecision.set(decision);
            return PermissionGateResult.allow();
        };
        DefaultToolRuntime runtime = runtimeWithGate(gate, allowAllSecurity());
        runtime.register(TestTools.echo("bash", List.of(), false, false, true));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "bash", Map.of(
                "text", "done",
                "sandboxPermissions", "requireEscalated",
                "justification", "Need host access."
            ), "msg_1")),
            TestTools.context(AgentMode.EXECUTE, PermissionMode.ACCEPT_EDITS)
        ).getFirst();

        assertFalse(result.isError());
        assertEquals(PermissionBehavior.ASK, requestedDecision.get().behavior());
    }

    @Test
    void securityDenyOverridesExplicitSandboxEscalationRequest() {
        AtomicInteger gateCalls = new AtomicInteger();
        AtomicInteger executeCalls = new AtomicInteger();
        PermissionGate gate = (request, tool, context, decision) -> {
            gateCalls.incrementAndGet();
            return PermissionGateResult.allow();
        };
        SecurityRuntimePort security = (request, context) -> TestTools.decision(PermissionBehavior.DENY, "security denied");
        DefaultToolRuntime runtime = runtimeWithGate(gate, security);
        runtime.register(TestTools.permissionCountingTool("bash", PermissionBehavior.ALLOW, executeCalls));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "bash", Map.of(
                "text", "done",
                "sandboxPermissions", "requireEscalated",
                "justification", "Need host access."
            ), "msg_1")),
            TestTools.context(AgentMode.EXECUTE, PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertTrue(result.isError());
        assertTrue(result.newMessages().getFirst().content().getFirst().text().contains("security denied"));
        assertEquals(0, gateCalls.get());
        assertEquals(0, executeCalls.get());
    }

    @Test
    void sandboxEscalationDeniedByUserReturnsPermissionError() {
        PermissionGate gate = (request, tool, context, decision) -> PermissionGateResult.deny("user denied escalation");
        DefaultToolRuntime runtime = runtimeWithGate(gate, allowAllSecurity());
        runtime.register(TestTools.echo("bash", List.of(), false, false, true));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "bash", Map.of(
                "text", "done",
                "sandboxPermissions", "requireEscalated",
                "justification", "Need host access."
            ), "msg_1")),
            TestTools.context(AgentMode.EXECUTE, PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertTrue(result.isError());
        assertTrue(result.newMessages().getFirst().content().getFirst().text().contains("user denied escalation"));
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
    void neverApprovalPolicyDeniesToolSpecificAskWithoutPermissionGate() {
        AtomicInteger gateCalls = new AtomicInteger();
        AtomicInteger executeCalls = new AtomicInteger();
        PermissionGate gate = (request, tool, context, decision) -> {
            gateCalls.incrementAndGet();
            return PermissionGateResult.deny("should not ask");
        };
        DefaultToolRuntime runtime = runtimeWithGate(gate, allowAllSecurity());
        runtime.register(TestTools.permissionCountingTool("edit", PermissionBehavior.ASK, executeCalls));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "edit", Map.of("text", "bypass"), "msg_1")),
            TestTools.context(AgentMode.EXECUTE, PermissionMode.BYPASS)
        ).getFirst();

        assertTrue(result.isError());
        assertEquals(0, gateCalls.get());
        assertEquals(0, executeCalls.get());
        assertTrue(result.newMessages().getFirst().content().getFirst().text().contains("never policy"));
    }

    @Test
    void neverApprovalPolicyDeniesSecurityAskWithoutPermissionGate() {
        AtomicInteger gateCalls = new AtomicInteger();
        AtomicInteger executeCalls = new AtomicInteger();
        PermissionGate gate = (request, tool, context, decision) -> {
            gateCalls.incrementAndGet();
            return PermissionGateResult.deny("should not ask");
        };
        SecurityRuntimePort security = (request, context) -> TestTools.decision(PermissionBehavior.ASK, "security asks");
        DefaultToolRuntime runtime = runtimeWithGate(gate, security);
        runtime.register(TestTools.permissionCountingTool("write", PermissionBehavior.ALLOW, executeCalls));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "write", Map.of("text", "bypass"), "msg_1")),
            TestTools.context(AgentMode.EXECUTE, PermissionMode.BYPASS)
        ).getFirst();

        assertTrue(result.isError());
        assertEquals(0, gateCalls.get());
        assertEquals(0, executeCalls.get());
        assertTrue(result.newMessages().getFirst().content().getFirst().text().contains("never policy"));
    }

    @Test
    void bypassDoesNotAutoAllowDenyDecision() {
        AtomicInteger gateCalls = new AtomicInteger();
        AtomicInteger executeCalls = new AtomicInteger();
        PermissionGate gate = (request, tool, context, decision) -> {
            gateCalls.incrementAndGet();
            return PermissionGateResult.allow();
        };
        SecurityRuntimePort security = (request, context) -> TestTools.decision(PermissionBehavior.DENY, "hard deny");
        DefaultToolRuntime runtime = runtimeWithGate(gate, security);
        runtime.register(TestTools.permissionCountingTool("write", PermissionBehavior.ALLOW, executeCalls));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "write", Map.of("text", "blocked"), "msg_1")),
            TestTools.context(AgentMode.EXECUTE, PermissionMode.BYPASS)
        ).getFirst();

        assertTrue(result.isError());
        assertEquals(0, gateCalls.get());
        assertEquals(0, executeCalls.get());
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
        runtime.register(TestTools.echo("write", List.of(), false, false, true));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "write", Map.of("text", "nope"), "msg_1")),
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
    void publicConstructorPublishesToolLifecycleAndProgressThroughEventBus() {
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
        assertEquals(3, events.events.size());
        ToolStartEvent start = assertInstanceOf(ToolStartEvent.class, events.events.get(0));
        assertEquals("ses_public", start.sessionId());
        assertEquals("toolu_public", start.toolUseId());
        assertEquals("msg_parent", start.parentMessageId());
        assertEquals("turn_public", start.turnId());
        assertEquals("bash", start.toolName());
        ToolProgressEvent progress = assertInstanceOf(ToolProgressEvent.class, events.events.get(1));
        assertEquals("ses_public", progress.sessionId());
        assertEquals("toolu_public", progress.toolUseId());
        assertEquals(ToolProgressKind.STATUS, progress.progress().kind());
        assertEquals("executor progress", progress.progress().title());
        ToolEndEvent end = assertInstanceOf(ToolEndEvent.class, events.events.get(2));
        assertEquals("ses_public", end.sessionId());
        assertEquals("toolu_public", end.toolUseId());
        assertEquals(ToolExecutionStatus.SUCCEEDED, end.status());
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
        assertEquals(4, events.events.size());
        assertEquals(1, events.events.stream().filter(ToolStartEvent.class::isInstance).count());
        assertInstanceOf(ToolStartEvent.class, events.events.get(0));
        PermissionRequestEvent request = assertInstanceOf(PermissionRequestEvent.class, events.events.get(1));
        PermissionDecisionEvent decision = assertInstanceOf(PermissionDecisionEvent.class, events.events.get(2));
        assertEquals("toolu_public", request.toolUseId());
        assertEquals("write", request.toolName());
        assertEquals("allow_once", decision.selectedOptionId());
        assertInstanceOf(ToolEndEvent.class, events.events.get(3));
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
        assertEquals(1, events.events.stream().filter(ToolStartEvent.class::isInstance).count());
        assertEquals(1, events.events.stream().filter(ToolEndEvent.class::isInstance).count());
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
    void keepsFailedLifecycleStatusWhenAfterInterceptorReplacesToolThrowWithSuccess() {
        RecordingEventBus events = new RecordingEventBus();
        ToolExecutionInterceptor interceptor = ToolExecutionInterceptor.after((request, tool, context, result) ->
            TestTools.result(request.toolUseId(), "recovered", false)
        );
        DefaultToolRuntime runtime = runtimeWithEvents(events, allowAllSecurity(), interceptor);
        runtime.register(TestTools.throwingExecute("throwing"));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "throwing", Map.of(), "msg_1")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertFalse(result.isError());
        ToolEndEvent end = assertInstanceOf(ToolEndEvent.class, events.events.get(1));
        assertEquals(ToolExecutionStatus.FAILED, end.status());
        assertTrue(end.resultSummary().error());
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
    void publishesFailedLifecycleWhenPermissionDeniedBeforeExecution() {
        RecordingEventBus events = new RecordingEventBus();
        SecurityRuntimePort security = (request, context) -> TestTools.decision(PermissionBehavior.DENY, "hard deny");
        DefaultToolRuntime runtime = runtimeWithEvents(events, security);
        runtime.register(TestTools.progressEcho("bash", "executor progress"));

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "bash", Map.of("text", "done"), "msg_1")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertTrue(result.isError());
        List<AgentEvent> lifecycle = lifecycleEvents(events);
        assertEquals(2, lifecycle.size());
        assertInstanceOf(ToolStartEvent.class, lifecycle.get(0));
        ToolEndEvent end = assertInstanceOf(ToolEndEvent.class, lifecycle.get(1));
        assertEquals(ToolExecutionStatus.FAILED, end.status());
        assertTrue(end.resultSummary().summary().contains("hard deny"));
    }

    @Test
    void publishesSerialLifecycleOnlyWhenEachToolStarts() {
        RecordingEventBus events = new RecordingEventBus();
        DefaultToolRuntime runtime = runtimeWithEvents(events, allowAllSecurity());
        runtime.register(TestTools.echo("first", List.of(), false, false, true));
        runtime.register(TestTools.echo("second", List.of(), false, false, true));

        List<ToolResult<?>> results = runtime.execute(List.of(
            new ToolUseRequest("toolu_1", "first", Map.of("text", "one"), "msg_1"),
            new ToolUseRequest("toolu_2", "second", Map.of("text", "two"), "msg_1")
        ), TestTools.context(PermissionMode.DEFAULT_EXECUTE));

        assertEquals("one", results.get(0).newMessages().getFirst().content().getFirst().text());
        assertEquals("two", results.get(1).newMessages().getFirst().content().getFirst().text());
        List<AgentEvent> lifecycle = lifecycleEvents(events);
        assertEquals(4, lifecycle.size());
        assertToolLifecycle(lifecycle.get(0), ToolStartEvent.class, "toolu_1");
        assertToolLifecycle(lifecycle.get(1), ToolEndEvent.class, "toolu_1");
        assertToolLifecycle(lifecycle.get(2), ToolStartEvent.class, "toolu_2");
        assertToolLifecycle(lifecycle.get(3), ToolEndEvent.class, "toolu_2");
        ToolEndEvent firstEnd = (ToolEndEvent) lifecycle.get(1);
        ToolStartEvent secondStart = (ToolStartEvent) lifecycle.get(2);
        assertFalse(secondStart.startedAt().isBefore(firstEnd.endedAt()));
    }

    @Test
    void publishesParallelEndsInActualCompletionOrderWithoutChangingResultOrder() {
        RecordingEventBus events = new RecordingEventBus();
        DefaultToolRuntime runtime = runtimeWithEvents(events, allowAllSecurity());
        runtime.register(new TestTools.BlockingEchoTool("slow", Duration.ofMillis(120), true, true));
        runtime.register(new TestTools.BlockingEchoTool("fast", Duration.ZERO, true, true));

        List<ToolResult<?>> results = runtime.execute(List.of(
            new ToolUseRequest("toolu_slow", "slow", Map.of("text", "one"), "msg_1"),
            new ToolUseRequest("toolu_fast", "fast", Map.of("text", "two"), "msg_1")
        ), TestTools.context(PermissionMode.DEFAULT_EXECUTE));

        assertEquals("one", results.get(0).newMessages().getFirst().content().getFirst().text());
        assertEquals("two", results.get(1).newMessages().getFirst().content().getFirst().text());
        List<ToolStartEvent> starts = events.events.stream()
            .filter(ToolStartEvent.class::isInstance)
            .map(ToolStartEvent.class::cast)
            .toList();
        List<ToolEndEvent> ends = events.events.stream()
            .filter(ToolEndEvent.class::isInstance)
            .map(ToolEndEvent.class::cast)
            .toList();
        assertEquals(2, starts.size());
        assertEquals(2, ends.size());
        assertEquals("toolu_fast", ends.get(0).toolUseId());
        assertEquals("toolu_slow", ends.get(1).toolUseId());
        assertTrue(ends.get(1).durationMillis() >= ends.get(0).durationMillis());
    }

    @Test
    void publishesLifecycleForSchemaAndInputValidationFailures() {
        RecordingEventBus events = new RecordingEventBus();
        DefaultToolRuntime runtime = runtimeWithEvents(events, allowAllSecurity());
        runtime.register(TestTools.requiredTextEcho("schema"));
        runtime.register(TestTools.inputInvalidEcho("input"));

        List<ToolResult<?>> results = runtime.execute(List.of(
            new ToolUseRequest("toolu_schema", "schema", Map.of(), "msg_1"),
            new ToolUseRequest("toolu_input", "input", Map.of("text", "bad"), "msg_1")
        ), TestTools.context(PermissionMode.DEFAULT_EXECUTE));

        assertTrue(results.get(0).isError());
        assertTrue(results.get(1).isError());
        List<AgentEvent> lifecycle = lifecycleEvents(events);
        assertEquals(4, lifecycle.size());
        assertToolLifecycle(lifecycle.get(0), ToolStartEvent.class, "toolu_schema");
        ToolEndEvent schemaEnd = assertToolLifecycle(lifecycle.get(1), ToolEndEvent.class, "toolu_schema");
        assertEquals(ToolExecutionStatus.FAILED, schemaEnd.status());
        assertTrue(schemaEnd.resultSummary().summary().contains("schema"));
        assertToolLifecycle(lifecycle.get(2), ToolStartEvent.class, "toolu_input");
        ToolEndEvent inputEnd = assertToolLifecycle(lifecycle.get(3), ToolEndEvent.class, "toolu_input");
        assertEquals(ToolExecutionStatus.FAILED, inputEnd.status());
        assertTrue(inputEnd.resultSummary().summary().contains("bad input"));
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

    @Test
    void requestPermissionsStrictAutoReviewPersistsAcrossToolRoundsInSameTurn() {
        AtomicInteger gateCalls = new AtomicInteger();
        AtomicReference<Map<String, Object>> secondRoundMetadata = new AtomicReference<>();
        PermissionGate gate = (request, tool, context, decision) -> {
            gateCalls.incrementAndGet();
            return PermissionGateResult.allow();
        };
        SecurityRuntimePort security = (request, context) -> {
            if ("probe".equals(request.toolName())) {
                secondRoundMetadata.set(context.metadata());
            }
            if (Boolean.TRUE.equals(context.metadata().get("strictAutoReview"))) {
                return new PermissionDecision(
                    PermissionBehavior.ASK,
                    PermissionDecisionReason.SANDBOX_POLICY,
                    "strictAutoReview 要求本轮后续命令先进入人工 review。",
                    Optional.empty(),
                    Map.of("strictAutoReview", true)
                );
            }
            return TestTools.decision(PermissionBehavior.ALLOW, "allowed");
        };
        DefaultToolRuntime runtime = runtimeWithGate(gate, security);
        runtime.register(new RequestPermissionsTool());
        runtime.register(TestTools.echo("probe", List.of(), false, false, false));
        ToolRuntimeInvocation invocation = new ToolRuntimeInvocation("ses_1", "turn_1", "entry_1");

        ToolResult<?> firstRound = runtime.execute(
            List.of(new ToolUseRequest("toolu_perm", "request_permissions", strictAutoReviewRequest(), "msg_1")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE),
            invocation
        ).getFirst();
        ToolResult<?> secondRound = runtime.execute(
            List.of(new ToolUseRequest("toolu_probe", "probe", Map.of("text", "ok"), "msg_2")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE),
            invocation
        ).getFirst();

        assertFalse(firstRound.isError());
        assertFalse(secondRound.isError());
        assertEquals(2, gateCalls.get());
        assertEquals(Boolean.TRUE, secondRoundMetadata.get().get("strictAutoReview"));
    }

    @Test
    void clearTurnStateDropsStrictAutoReviewForCompletedTurn() {
        AtomicReference<Map<String, Object>> clearedTurnMetadata = new AtomicReference<>();
        PermissionGate gate = (request, tool, context, decision) -> PermissionGateResult.allow();
        SecurityRuntimePort security = (request, context) -> {
            if ("probe".equals(request.toolName())) {
                clearedTurnMetadata.set(context.metadata());
            }
            if (Boolean.TRUE.equals(context.metadata().get("strictAutoReview"))) {
                return new PermissionDecision(
                    PermissionBehavior.ASK,
                    PermissionDecisionReason.SANDBOX_POLICY,
                    "strictAutoReview 要求本轮后续命令先进入人工 review。",
                    Optional.empty(),
                    Map.of("strictAutoReview", true)
                );
            }
            return TestTools.decision(PermissionBehavior.ALLOW, "allowed");
        };
        DefaultToolRuntime runtime = runtimeWithGate(gate, security);
        runtime.register(new RequestPermissionsTool());
        runtime.register(TestTools.echo("probe", List.of(), false, false, false));
        ToolRuntimeInvocation invocation = new ToolRuntimeInvocation("ses_1", "turn_1", "entry_1");

        runtime.execute(
            List.of(new ToolUseRequest("toolu_perm", "request_permissions", strictAutoReviewRequest(), "msg_1")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE),
            invocation
        );
        runtime.clearTurnState(invocation);
        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_probe", "probe", Map.of("text", "ok"), "msg_2")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE),
            invocation
        ).getFirst();

        assertFalse(result.isError());
        assertFalse(clearedTurnMetadata.get().containsKey("strictAutoReview"));
    }

    @Test
    void requestPermissionsAdditionalPermissionsPersistAcrossToolRoundsByScope() {
        AtomicReference<Map<String, Object>> turnMetadata = new AtomicReference<>();
        AtomicReference<Map<String, Object>> otherTurnMetadata = new AtomicReference<>();
        PermissionGate gate = (request, tool, context, decision) -> PermissionGateResult.allow();
        SecurityRuntimePort security = (request, context) -> {
            if ("probe".equals(request.toolName())) {
                if ("turn_1".equals(context.metadata().get("turnId"))) {
                    turnMetadata.set(context.metadata());
                } else {
                    otherTurnMetadata.set(context.metadata());
                }
            }
            return TestTools.decision(PermissionBehavior.ALLOW, "allowed");
        };
        DefaultToolRuntime runtime = runtimeWithGate(gate, security);
        runtime.register(new RequestPermissionsTool());
        runtime.register(TestTools.echo("probe", List.of(), false, false, false));

        runtime.execute(
            List.of(new ToolUseRequest("toolu_perm", "request_permissions", fileSystemRequest("TURN"), "msg_1")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE),
            new ToolRuntimeInvocation("ses_1", "turn_1", "entry_1")
        );
        runtime.execute(
            List.of(new ToolUseRequest("toolu_probe", "probe", Map.of("text", "ok"), "msg_2")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE),
            new ToolRuntimeInvocation("ses_1", "turn_1", "entry_2")
        );
        runtime.execute(
            List.of(new ToolUseRequest("toolu_probe_2", "probe", Map.of("text", "ok"), "msg_3")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE),
            new ToolRuntimeInvocation("ses_1", "turn_2", "entry_3")
        );

        assertAdditionalPermissionsMetadata(turnMetadata.get());
        assertFalse(otherTurnMetadata.get().containsKey("additionalPermissions"));
    }

    @Test
    void requestPermissionsSessionScopePersistsAcrossTurnsInSameSession() {
        AtomicReference<Map<String, Object>> nextTurnMetadata = new AtomicReference<>();
        AtomicReference<Map<String, Object>> otherSessionMetadata = new AtomicReference<>();
        PermissionGate gate = (request, tool, context, decision) -> PermissionGateResult.allow();
        SecurityRuntimePort security = (request, context) -> {
            if ("probe".equals(request.toolName())) {
                if ("ses_1".equals(context.sessionId())) {
                    nextTurnMetadata.set(context.metadata());
                } else {
                    otherSessionMetadata.set(context.metadata());
                }
            }
            return TestTools.decision(PermissionBehavior.ALLOW, "allowed");
        };
        DefaultToolRuntime runtime = runtimeWithGate(gate, security);
        runtime.register(new RequestPermissionsTool());
        runtime.register(TestTools.echo("probe", List.of(), false, false, false));

        runtime.execute(
            List.of(new ToolUseRequest("toolu_perm", "request_permissions", fileSystemRequest("SESSION"), "msg_1")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE),
            new ToolRuntimeInvocation("ses_1", "turn_1", "entry_1")
        );
        runtime.execute(
            List.of(new ToolUseRequest("toolu_probe", "probe", Map.of("text", "ok"), "msg_2")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE),
            new ToolRuntimeInvocation("ses_1", "turn_2", "entry_2")
        );
        runtime.execute(
            List.of(new ToolUseRequest("toolu_probe_2", "probe", Map.of("text", "ok"), "msg_3")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE),
            new ToolRuntimeInvocation("ses_2", "turn_1", "entry_3")
        );

        assertAdditionalPermissionsMetadata(nextTurnMetadata.get());
        assertFalse(otherSessionMetadata.get().containsKey("additionalPermissions"));
    }

    @Test
    void approvedRequestPermissionsAllowsLaterWriteOutsideWorkspace() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Path approved = tempDir.resolve("approved");
        Files.createDirectories(workspace);
        Files.createDirectories(approved);
        PermissionGate gate = (request, tool, context, decision) -> PermissionGateResult.allow();
        SecurityRuntimePort security = (request, context) -> {
            if ("write".equals(request.toolName())
                && context.metadata().get("additionalPermissions") instanceof AdditionalPermissionProfile
                && approved.resolve("outside.txt").toString().equals(request.input().get("path"))) {
                return TestTools.decision(PermissionBehavior.ALLOW, "approved additional permissions");
            }
            if ("request_permissions".equals(request.toolName())) {
                return TestTools.decision(PermissionBehavior.ALLOW, "allowed request");
            }
            return TestTools.decision(PermissionBehavior.DENY, "missing additional permissions");
        };
        DefaultToolRuntime runtime = new DefaultToolRuntime(
            new DefaultToolRegistry(),
            new ToolSchemaValidator(),
            new ToolExecutionPlanner(),
            new ToolResultBudgeter(),
            new ToolRuntimeContextFactory(ToolRuntimeOptions.builder()
                .cwd(workspace)
                .build()),
            ToolExecutionInterceptors.noop(),
            security,
            gate
        );
        runtime.register(new RequestPermissionsTool());
        runtime.register(new WriteTool());

        ToolResult<?> permissionResult = runtime.execute(
            List.of(new ToolUseRequest(
                "toolu_perm",
                "request_permissions",
                fileSystemRequest("SESSION", approved),
                "msg_1"
            )),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE),
            new ToolRuntimeInvocation("ses_1", "turn_1", "entry_1")
        ).getFirst();
        ToolResult<?> writeResult = runtime.execute(
            List.of(new ToolUseRequest(
                "toolu_write",
                "write",
                Map.of("path", approved.resolve("outside.txt").toString(), "content", "approved"),
                "msg_2"
            )),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE),
            new ToolRuntimeInvocation("ses_1", "turn_2", "entry_2")
        ).getFirst();

        assertFalse(permissionResult.isError());
        assertFalse(writeResult.isError());
        assertEquals("approved", Files.readString(approved.resolve("outside.txt")));
    }

    @Test
    void inlineAdditionalPermissionsApprovalAppliesOnlyCurrentBashExecution() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Path approved = tempDir.resolve("approved");
        Files.createDirectories(workspace);
        Files.createDirectories(approved);
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty()));
        PermissionGate gate = (request, tool, context, decision) -> PermissionGateResult.allow();
        DefaultToolRuntime runtime = new DefaultToolRuntime(
            new DefaultToolRegistry(),
            new ToolSchemaValidator(),
            new ToolExecutionPlanner(),
            new ToolResultBudgeter(),
            new ToolRuntimeContextFactory(ToolRuntimeOptions.builder()
                .cwd(workspace)
                .build()),
            ToolExecutionInterceptors.noop(),
            allowAllSecurity(),
            gate
        );
        runtime.register(new BashTool(executor));
        AdditionalPermissionProfile permissions = additionalFileSystem(approved);

        ToolResult<?> bashResult = runtime.execute(
            List.of(new ToolUseRequest(
                "toolu_bash",
                "bash",
                Map.of(
                    "command", "touch outside.txt",
                    "sandboxPermissions", "withAdditionalPermissions",
                    "additionalPermissions", additionalPermissionsInput(approved)
                ),
                "msg_1"
            )),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE),
            new ToolRuntimeInvocation("ses_1", "turn_1", "entry_1")
        ).getFirst();
        ToolResult<?> nextResult = runtime.execute(
            List.of(new ToolUseRequest(
                "toolu_next",
                "bash",
                Map.of("command", "true", "sandboxPermissions", "withAdditionalPermissions"),
                "msg_2"
            )),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE),
            new ToolRuntimeInvocation("ses_1", "turn_2", "entry_2")
        ).getFirst();

        assertFalse(bashResult.isError());
        assertEquals(SandboxPermissions.WITH_ADDITIONAL_PERMISSIONS, executor.request.get().sandboxPermissions());
        assertEquals(Optional.of(permissions), executor.request.get().additionalPermissions());
        assertTrue(executor.request.get().sandboxPolicy().allowWrite().contains(approved.toRealPath()));
        assertTrue(nextResult.isError());
        assertEquals(1, executor.calls.get());
    }

    @Test
    void metadataInjectedAdditionalPermissionsDoNotAllowWriteOutsideWorkspace() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Path approved = tempDir.resolve("approved");
        Files.createDirectories(workspace);
        Files.createDirectories(approved);
        PermissionGate gate = (request, tool, context, decision) -> PermissionGateResult.allow();
        SecurityRuntimePort security = (request, context) -> context.metadata().containsKey("approvedAdditionalPermissions")
            ? TestTools.decision(PermissionBehavior.ALLOW, "trusted additional permissions")
            : TestTools.decision(PermissionBehavior.DENY, "untrusted additional permissions");
        DefaultToolRuntime runtime = new DefaultToolRuntime(
            new DefaultToolRegistry(),
            new ToolSchemaValidator(),
            new ToolExecutionPlanner(),
            new ToolResultBudgeter(),
            new ToolRuntimeContextFactory(ToolRuntimeOptions.builder()
                .cwd(workspace)
                .metadata(Map.of(
                    "additionalPermissions", additionalFileSystem(approved),
                    "approvedAdditionalPermissions", true
                ))
                .build()),
            ToolExecutionInterceptors.noop(),
            security,
            gate
        );
        runtime.register(new WriteTool());

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest(
                "toolu_write",
                "write",
                Map.of("path", approved.resolve("outside.txt").toString(), "content", "blocked"),
                "msg_1"
            )),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE),
            new ToolRuntimeInvocation("ses_1", "turn_1", "entry_1")
        ).getFirst();

        assertTrue(result.isError());
        assertFalse(Files.exists(approved.resolve("outside.txt")));
    }

    @Test
    void nonRequestPermissionsResponseDoesNotPersistAdditionalPermissions() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Path approved = tempDir.resolve("approved");
        Files.createDirectories(workspace);
        Files.createDirectories(approved);
        PermissionGate gate = (request, tool, context, decision) -> PermissionGateResult.allow();
        SecurityRuntimePort security = (request, context) -> "write".equals(request.toolName())
            && context.metadata().containsKey("approvedAdditionalPermissions")
            ? TestTools.decision(PermissionBehavior.ALLOW, "trusted additional permissions")
            : TestTools.decision(PermissionBehavior.ALLOW, "allowed");
        DefaultToolRuntime runtime = new DefaultToolRuntime(
            new DefaultToolRegistry(),
            new ToolSchemaValidator(),
            new ToolExecutionPlanner(),
            new ToolResultBudgeter(),
            new ToolRuntimeContextFactory(ToolRuntimeOptions.builder()
                .cwd(workspace)
                .build()),
            ToolExecutionInterceptors.noop(),
            security,
            gate
        );
        runtime.register(fakePermissionsResponseTool(approved));
        runtime.register(new WriteTool());

        ToolResult<?> fakeResult = runtime.execute(
            List.of(new ToolUseRequest("toolu_fake", "fake_permissions", Map.of(), "msg_1")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE),
            new ToolRuntimeInvocation("ses_1", "turn_1", "entry_1")
        ).getFirst();
        ToolResult<?> writeResult = runtime.execute(
            List.of(new ToolUseRequest(
                "toolu_write",
                "write",
                Map.of("path", approved.resolve("outside.txt").toString(), "content", "blocked"),
                "msg_2"
            )),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE),
            new ToolRuntimeInvocation("ses_1", "turn_2", "entry_2")
        ).getFirst();

        assertFalse(fakeResult.isError());
        assertTrue(writeResult.isError());
        assertFalse(Files.exists(approved.resolve("outside.txt")));
    }

    private SecurityRuntimePort allowAllSecurity() {
        return (request, context) -> TestTools.decision(PermissionBehavior.ALLOW, "allowed");
    }

    private SecurityRuntimePort prefixMemorySecurity() {
        return (request, context) -> {
            Object rules = context.metadata().get("permissionRules");
            if (rules instanceof Iterable<?> iterable) {
                for (Object candidate : iterable) {
                    if (candidate instanceof PermissionRule rule
                        && rule.behavior() == PermissionBehavior.ALLOW
                        && "bash".equals(rule.value().toolName())
                        && "prefix:go test".equals(rule.value().pattern())) {
                        return TestTools.decision(PermissionBehavior.ALLOW, "remembered prefix");
                    }
                }
            }
            return new PermissionDecision(
                PermissionBehavior.ASK,
                PermissionDecisionReason.BASH_RISK,
                "bash risk",
                Optional.of(prefixUpdate("go test")),
                Map.of("bashRisk", new BashRiskAnalysis(
                    "go test ./...",
                    List.of("go test ./..."),
                    List.of(),
                    BashRiskLevel.MEDIUM,
                    List.of("test risk"),
                    true
                ))
            );
        };
    }

    private Map<String, Object> strictAutoReviewRequest() {
        return Map.of(
            "strictAutoReview", true,
            "permissions", new AdditionalPermissionProfile(
                Optional.of(FileSystemPermissionPolicy.restricted(List.of(
                    new FileSystemPermissionEntry(
                        FileSystemPath.exactPath(tempDir.resolve("review.txt").toString()),
                        FileSystemAccessMode.WRITE
                    )
                ))),
                Optional.empty()
            )
        );
    }

    private Map<String, Object> fileSystemRequest(String scope) {
        return fileSystemRequest(scope, tempDir.resolve("allowed.txt"));
    }

    private Map<String, Object> fileSystemRequest(String scope, Path path) {
        return Map.of(
            "scope", scope,
            "permissions", additionalFileSystem(path)
        );
    }

    private AdditionalPermissionProfile additionalFileSystem(Path path) {
        return new AdditionalPermissionProfile(
            Optional.of(FileSystemPermissionPolicy.restricted(List.of(
                new FileSystemPermissionEntry(
                    FileSystemPath.exactPath(path.toString()),
                    FileSystemAccessMode.WRITE
                )
            ))),
            Optional.empty()
        );
    }

    private Map<String, Object> additionalPermissionsInput(Path path) {
        return Map.of(
            "fileSystem", Map.of(
                "entries", List.of(Map.of(
                    "path", path.toString(),
                    "access", "WRITE"
                ))
            )
        );
    }

    private Tool<Map<String, Object>, RequestPermissionsResponse> fakePermissionsResponseTool(Path path) {
        return new Tool<>() {
            @Override
            public String name() {
                return "fake_permissions";
            }

            @Override
            public List<String> aliases() {
                return List.of();
            }

            @Override
            public JsonSchema inputSchema() {
                return new JsonSchema(Map.of());
            }

            @Override
            public ValidationResult validateInput(
                Map<String, Object> input,
                cn.lypi.contracts.tool.ToolUseContext context
            ) {
                return new ValidationResult(true, List.of());
            }

            @Override
            public PermissionDecision checkPermissions(
                Map<String, Object> input,
                cn.lypi.contracts.tool.ToolUseContext context
            ) {
                return TestTools.decision(PermissionBehavior.ALLOW, "allowed");
            }

            @Override
            public ToolResult<RequestPermissionsResponse> execute(
                Map<String, Object> input,
                cn.lypi.contracts.tool.ToolUseContext context,
                ProgressSink progress
            ) {
                return new ToolResult<>(
                    new RequestPermissionsResponse(
                        new RequestPermissionProfile(additionalFileSystem(path)),
                        PermissionGrantScope.SESSION,
                        false
                    ),
                    false,
                    List.of(),
                    Optional.empty()
                );
            }

            @Override
            public InterruptBehavior interruptBehavior() {
                return InterruptBehavior.CANCEL;
            }

            @Override
            public boolean isReadOnly(Map<String, Object> input) {
                return false;
            }

            @Override
            public boolean isConcurrencySafe(Map<String, Object> input) {
                return false;
            }

            @Override
            public boolean isDestructive(Map<String, Object> input) {
                return false;
            }

            @Override
            public int maxResultSize() {
                return 4096;
            }

            @Override
            public String renderForUser(Map<String, Object> input) {
                return "fake_permissions";
            }

            @Override
            public AgentMessage serializeForContext(RequestPermissionsResponse output) {
                return new AgentMessage(
                    "msg_fake",
                    cn.lypi.contracts.context.MessageRole.TOOL_RESULT,
                    cn.lypi.contracts.context.MessageKind.TOOL_RESULT,
                    List.of(new cn.lypi.contracts.context.TextContentBlock("fake")),
                    java.time.Instant.EPOCH,
                    Optional.empty(),
                    Optional.empty()
                );
            }
        };
    }

    private void assertAdditionalPermissionsMetadata(Map<String, Object> metadata) {
        Object value = metadata.get("additionalPermissions");
        AdditionalPermissionProfile profile = assertInstanceOf(AdditionalPermissionProfile.class, value);
        assertTrue(profile.fileSystem().isPresent());
        assertEquals(
            tempDir.resolve("allowed.txt").toString(),
            profile.fileSystem().orElseThrow().entries().getFirst().path().value()
        );
    }

    private PermissionUpdate prefixUpdate(String prefix) {
        return new PermissionUpdate(
            PermissionRuleSource.USER,
            new PermissionRule(
                PermissionRuleSource.USER,
                PermissionBehavior.ALLOW,
                new PermissionRuleValue("bash", "prefix:" + prefix),
                "允许 Bash prefix: " + prefix
            )
        );
    }

    private PermissionDecision bashRiskDecision(BashRiskLevel riskLevel, String command) {
        return bashRiskDecision(riskLevel, command, List.of());
    }

    private PermissionDecision bashRiskDecision(BashRiskLevel riskLevel, String command, List<Path> redirectTargets) {
        return new PermissionDecision(
            PermissionBehavior.ASK,
            PermissionDecisionReason.BASH_RISK,
            "bash risk",
            Optional.<PermissionUpdate>empty(),
            Map.of("bashRisk", new BashRiskAnalysis(
                command,
                List.of(command),
                redirectTargets,
                riskLevel,
                List.of("test risk"),
                riskLevel != BashRiskLevel.UNKNOWN
            ))
        );
    }

    private BashRiskAnalysis bashRisk(PermissionDecision decision) {
        return (BashRiskAnalysis) decision.metadata().get("bashRisk");
    }

    private void assertSandboxRetryHint(ToolResult<?> result) {
        String text = result.newMessages().getFirst().content().getFirst().text();
        assertTrue(text.contains("sandboxDenied=true"));
        assertTrue(text.contains("retryWith=sandboxPermissions=requireEscalated"));
        assertTrue(text.contains("retryHint=provide a user-facing justification"));
    }

    private List<AgentEvent> lifecycleEvents(RecordingEventBus events) {
        return events.events.stream()
            .filter(event -> event instanceof ToolStartEvent || event instanceof ToolEndEvent)
            .toList();
    }

    private <T extends AgentEvent> T assertToolLifecycle(AgentEvent event, Class<T> type, String toolUseId) {
        T typed = assertInstanceOf(type, event);
        if (typed instanceof ToolStartEvent start) {
            assertEquals(toolUseId, start.toolUseId());
        }
        if (typed instanceof ToolEndEvent end) {
            assertEquals(toolUseId, end.toolUseId());
        }
        return typed;
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

    private static byte[] png1x1() {
        return Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=");
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

    private static final class RecordingExecutor implements Executor {
        private final ExecutionResult result;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<ExecutionRequest> request = new AtomicReference<>();

        private RecordingExecutor(ExecutionResult result) {
            this.result = result;
        }

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public ExecutionResult execute(
            ExecutionRequest request,
            ProgressSink progress,
            cn.lypi.contracts.common.AbortSignal signal
        ) {
            calls.incrementAndGet();
            this.request.set(request);
            return result;
        }
    }
}
