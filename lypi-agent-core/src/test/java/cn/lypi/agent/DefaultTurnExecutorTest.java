package cn.lypi.agent;

import cn.lypi.agent.compact.CompactionCoordinator;
import cn.lypi.agent.compact.CompactionDecision;
import cn.lypi.agent.compact.NoopCompactionCoordinator;
import cn.lypi.contracts.agent.PermissionResumeRequest;
import cn.lypi.contracts.agent.TurnRequest;
import cn.lypi.contracts.agent.TurnState;
import cn.lypi.contracts.agent.TurnStatus;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.ErrorEvent;
import cn.lypi.contracts.event.MessageDeltaEvent;
import cn.lypi.contracts.event.MessageEndEvent;
import cn.lypi.contracts.event.MessageStartEvent;
import cn.lypi.contracts.event.PermissionDecisionEvent;
import cn.lypi.contracts.event.PermissionRequestEvent;
import cn.lypi.contracts.event.TurnEndEvent;
import cn.lypi.contracts.event.TurnStartEvent;
import cn.lypi.contracts.event.ToolEndEvent;
import cn.lypi.contracts.event.ToolStartEvent;
import cn.lypi.contracts.model.AssistantDone;
import cn.lypi.contracts.model.AssistantError;
import cn.lypi.contracts.model.AssistantStart;
import cn.lypi.contracts.model.TextDelta;
import cn.lypi.contracts.model.ToolCallDelta;
import cn.lypi.contracts.runtime.PendingToolPermissionException;
import cn.lypi.contracts.security.PendingPermission;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionResponse;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.PermissionDecisionEntry;
import cn.lypi.contracts.session.PermissionPendingEntry;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static cn.lypi.agent.AgentCoreTestFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultTurnExecutorTest {
    @Test
    void executesSimpleTurnWithoutTools() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        provider.enqueue(List.of(
            new AssistantStart("msg-assistant"),
            new TextDelta("hi"),
            new AssistantDone(Optional.empty(), Optional.of("end_turn"))
        ));

        ContextAssembler assembler = request -> new ContextAssembly(
            AgentCoreTestFixtures.minimalContext(session.messages()),
            List.of(),
            List.of(),
            List.of(),
            false
        );
        DefaultTurnExecutor executor = new DefaultTurnExecutor(
            AgentCoreTestFixtures.ports(
                session,
                provider,
                tools,
                eventBus,
                assembler,
                new NoopCompactionCoordinator(),
                new NoopMemoryExtractionWorker()
            ),
            TurnIds.fixed("turn-1", "msg-user", "entry-1"),
            clock
        );

        TurnState state = executor.execute(new TurnRequest(
            "session-1",
            "hello",
            Optional.empty(),
            () -> false
        ));

        assertThat(state.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(session.messages()).extracting(AgentMessage::role)
            .containsExactly(MessageRole.USER, MessageRole.ASSISTANT);
        assertThat(session.messages()).extracting(AgentMessage::id)
            .containsExactly("msg-user", "msg-assistant");
        assertThat(provider.contexts).hasSize(1);
        assertThat(eventBus.events).extracting(AgentEvent::getClass)
            .containsExactly(
                TurnStartEvent.class,
                MessageStartEvent.class,
                MessageEndEvent.class,
                MessageStartEvent.class,
                MessageDeltaEvent.class,
                MessageEndEvent.class,
                TurnEndEvent.class
            );
        assertThat(((TurnEndEvent) eventBus.events.getLast()).status()).isEqualTo("COMPLETED");
    }

    @Test
    void executesToolCallsAndContinuesModelLoop() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        provider.enqueue(List.of(
            new AssistantStart("msg-tool-call"),
            new ToolCallDelta("toolu-1", "read", Map.of("path", "pom.xml"), true),
            new AssistantDone(Optional.empty(), Optional.of("tool_calls"))
        ));
        provider.enqueue(List.of(
            new AssistantStart("msg-final"),
            new TextDelta("done"),
            new AssistantDone(Optional.empty(), Optional.of("end_turn"))
        ));
        tools.enqueue(List.of(new ToolResult<>(
            "ok",
            false,
            List.of(AgentCoreTestFixtures.toolResultMessage("msg-tool-result", "toolu-1", "content", false)),
            Optional.empty()
        )));

        ContextAssembler assembler = request -> new ContextAssembly(
            AgentCoreTestFixtures.minimalContext(session.messages()),
            List.of(),
            List.of(),
            List.of(),
            false
        );
        DefaultTurnExecutor executor = new DefaultTurnExecutor(
            AgentCoreTestFixtures.ports(
                session,
                provider,
                tools,
                eventBus,
                assembler,
                new NoopCompactionCoordinator(),
                new NoopMemoryExtractionWorker()
            ),
            TurnIds.fixed("turn-1", "msg-user", "msg-fallback-1", "msg-fallback-2"),
            clock
        );

        TurnState state = executor.execute(new TurnRequest(
            "session-1",
            "read pom",
            Optional.empty(),
            () -> false
        ));

        assertThat(state.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(provider.contexts).hasSize(2);
        assertThat(tools.requests).hasSize(1);
        assertThat(tools.requests.getFirst()).extracting(request -> request.toolUseId())
            .containsExactly("toolu-1");
        assertThat(session.messages()).extracting(AgentMessage::id)
            .containsExactly("msg-user", "msg-tool-call", "msg-tool-result", "msg-final");
        assertThat(session.messages()).extracting(AgentMessage::role)
            .containsExactly(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL_RESULT, MessageRole.ASSISTANT);
    }

    @Test
    void waitsForPermissionWhenToolRuntimeRaisesPendingPermission() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        provider.enqueue(List.of(
            new AssistantStart("msg-tool-call"),
            new ToolCallDelta("toolu-1", "write", Map.of("path", "README.md"), true),
            new AssistantDone(Optional.empty(), Optional.of("tool_calls"))
        ));
        tools.failWith(new PendingToolPermissionException(pendingPermission("turn-1", "toolu-1")));
        DefaultTurnExecutor executor = executor(
            session,
            provider,
            tools,
            eventBus,
            clock,
            "turn-1",
            "msg-user",
            "msg-fallback",
            "entry-pending"
        );

        TurnState state = executor.execute(new TurnRequest("session-1", "write file", Optional.empty(), () -> false));

        assertThat(state.status()).isEqualTo(TurnStatus.WAITING_PERMISSION);
        assertThat(state.pendingPermission()).isPresent();
        assertThat(state.pendingPermission().orElseThrow().toolUseId()).isEqualTo("toolu-1");
        assertThat(session.handle().byId().values()).anyMatch(PermissionPendingEntry.class::isInstance);
        assertThat(eventBus.events).anyMatch(PermissionRequestEvent.class::isInstance);
        assertThat(eventBus.events).noneMatch(event -> event instanceof ToolEndEvent toolEnd && toolEnd.toolUseId().equals("toolu-1"));
    }

    @Test
    void resumePermissionDenyFeedsErrorToolResultBackToModel() {
        AgentCoreTestFixtures.InMemorySessionManager session = sessionWaitingForPermission();
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        provider.enqueue(List.of(
            new AssistantStart("msg-final"),
            new TextDelta("I will not write it."),
            new AssistantDone(Optional.empty(), Optional.of("end_turn"))
        ));
        DefaultTurnExecutor executor = executor(
            session,
            provider,
            tools,
            eventBus,
            clock,
            "entry-decision",
            "msg-denied",
            "msg-fallback"
        );

        TurnState state = executor.resumePermission(new PermissionResumeRequest(new PermissionResponse(
            "session-1",
            "turn-1",
            "toolu-1",
            PermissionBehavior.DENY,
            Optional.of("user denied"),
            Optional.empty()
        )));

        assertThat(state.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(tools.resumeRequests).isEmpty();
        assertThat(session.messages()).extracting(AgentMessage::role)
            .containsExactly(MessageRole.ASSISTANT, MessageRole.TOOL_RESULT, MessageRole.ASSISTANT);
        assertThat(session.messages().get(1).content().getFirst().text()).contains("user denied");
        assertThat(provider.contexts).hasSize(1);
        assertThat(provider.contexts.getFirst().messages()).extracting(AgentMessage::role)
            .contains(MessageRole.TOOL_RESULT);
        assertThat(session.handle().byId().values()).anyMatch(PermissionDecisionEntry.class::isInstance);
        assertThat(eventBus.events).anyMatch(PermissionDecisionEvent.class::isInstance);
        assertThat(eventBus.events.stream()
            .filter(event -> event instanceof ToolStartEvent || event instanceof ToolEndEvent)
            .map(event -> event.getClass().getSimpleName() + ":" + toolUseId(event) + ":" + toolError(event))
            .toList())
            .containsExactly("ToolEndEvent:toolu-1:true");
    }

    @Test
    void resumePermissionAllowRunsOriginalToolAndContinuesModelLoop() {
        AgentCoreTestFixtures.InMemorySessionManager session = sessionWaitingForPermission();
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        tools.enqueueResume(toolResult("toolu-1", "written", false));
        provider.enqueue(List.of(
            new AssistantStart("msg-final"),
            new TextDelta("done"),
            new AssistantDone(Optional.empty(), Optional.of("end_turn"))
        ));
        DefaultTurnExecutor executor = executor(session, provider, tools, eventBus, clock, "entry-decision", "msg-fallback");

        TurnState state = executor.resumePermission(new PermissionResumeRequest(new PermissionResponse(
            "session-1",
            "turn-1",
            "toolu-1",
            PermissionBehavior.ALLOW,
            Optional.empty(),
            Optional.empty()
        )));

        assertThat(state.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(tools.resumeRequests).extracting(ToolUseRequest::toolUseId).containsExactly("toolu-1");
        assertThat(tools.resumeResponses).extracting(PermissionResponse::behavior).containsExactly(PermissionBehavior.ALLOW);
        assertThat(session.messages()).extracting(AgentMessage::role)
            .containsExactly(MessageRole.ASSISTANT, MessageRole.TOOL_RESULT, MessageRole.ASSISTANT);
        assertThat(session.messages().get(1).content().getFirst().text()).isEqualTo("written");
        assertThat(eventBus.events.stream()
            .filter(event -> event instanceof ToolStartEvent || event instanceof ToolEndEvent)
            .map(event -> event.getClass().getSimpleName() + ":" + toolUseId(event) + ":" + toolError(event))
            .toList())
            .containsExactly("ToolEndEvent:toolu-1:false");
    }

    @Test
    void resumePermissionAbortEndsTurnWithoutCallingModel() {
        AgentCoreTestFixtures.InMemorySessionManager session = sessionWaitingForPermission();
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        DefaultTurnExecutor executor = executor(session, provider, tools, eventBus, clock, "entry-decision", "msg-fallback");

        TurnState state = executor.resumePermission(new PermissionResumeRequest(new PermissionResponse(
            "session-1",
            "turn-1",
            "toolu-1",
            PermissionBehavior.ABORT,
            Optional.of("user aborted"),
            Optional.empty()
        )));

        assertThat(state.status()).isEqualTo(TurnStatus.ABORTED);
        assertThat(provider.contexts).isEmpty();
        assertThat(tools.resumeRequests).isEmpty();
        assertThat(eventBus.events).anyMatch(PermissionDecisionEvent.class::isInstance);
        assertThat(eventBus.events.stream()
            .filter(event -> event instanceof ToolStartEvent || event instanceof ToolEndEvent)
            .map(event -> event.getClass().getSimpleName() + ":" + toolUseId(event) + ":" + toolError(event))
            .toList())
            .containsExactly("ToolEndEvent:toolu-1:true");
        assertThat(((TurnEndEvent) eventBus.events.getLast()).status()).isEqualTo("ABORTED");
    }

    @Test
    void resumePermissionRejectsAlreadyDecidedPendingRequest() {
        AgentCoreTestFixtures.InMemorySessionManager session = sessionWaitingForPermission();
        session.switchLeaf("entry-pending");
        session.append(new PermissionDecisionEntry(
            "entry-existing-decision",
            "entry-pending",
            new PermissionResponse(
                "session-1",
                "turn-1",
                "toolu-1",
                PermissionBehavior.DENY,
                Optional.of("already denied"),
                Optional.empty()
            ),
            NOW
        ));
        DefaultTurnExecutor executor = executor(
            session,
            new AgentCoreTestFixtures.StubAiProvider(),
            new AgentCoreTestFixtures.StubToolRuntime(),
            new AgentCoreTestFixtures.RecordingEventBus(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            "entry-decision",
            "msg-fallback"
        );

        assertThatThrownBy(() -> executor.resumePermission(new PermissionResumeRequest(new PermissionResponse(
            "session-1",
            "turn-1",
            "toolu-1",
            PermissionBehavior.ALLOW,
            Optional.empty(),
            Optional.empty()
        ))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("未找到等待中的权限请求");
    }

    @Test
    void resumePermissionFindsPendingEvenWhenCurrentLeafMoved() {
        AgentCoreTestFixtures.InMemorySessionManager session = sessionWaitingForPermission();
        session.switchLeaf("entry-msg-tool-call");
        session.append(new MessageEntry(
            "entry-other",
            "entry-msg-tool-call",
            AgentCoreTestFixtures.userMessage("msg-other", "other branch"),
            NOW
        ));
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        provider.enqueue(List.of(
            new AssistantStart("msg-final"),
            new TextDelta("denied"),
            new AssistantDone(Optional.empty(), Optional.of("end_turn"))
        ));
        DefaultTurnExecutor executor = executor(
            session,
            provider,
            new AgentCoreTestFixtures.StubToolRuntime(),
            new AgentCoreTestFixtures.RecordingEventBus(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            "entry-decision",
            "msg-denied",
            "msg-fallback"
        );

        TurnState state = executor.resumePermission(new PermissionResumeRequest(new PermissionResponse(
            "session-1",
            "turn-1",
            "toolu-1",
            PermissionBehavior.DENY,
            Optional.of("no"),
            Optional.empty()
        )));

        assertThat(state.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(session.branch(session.leafId())).extracting(SessionEntry::id)
            .containsExactly(
                "entry-msg-tool-call",
                "entry-pending",
                "entry-decision",
                "entry-msg-denied",
                "entry-msg-final"
            );
        assertThat(session.branch("entry-other")).extracting(SessionEntry::id)
            .containsExactly("entry-msg-tool-call", "entry-other");
    }

    @Test
    void resumePermissionContinuesWithStoredToolRoundLimit() {
        AgentCoreTestFixtures.InMemorySessionManager session = sessionWaitingForPermission(1, 1);
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        tools.enqueueResume(toolResult("toolu-1", "written", false));
        provider.enqueue(List.of(
            new AssistantStart("msg-tool-call-2"),
            new ToolCallDelta("toolu-2", "read", Map.of("path", "README.md"), true),
            new AssistantDone(Optional.empty(), Optional.of("tool_calls"))
        ));
        DefaultTurnExecutor executor = executor(
            session,
            provider,
            tools,
            eventBus,
            Clock.fixed(NOW, ZoneOffset.UTC),
            "entry-decision",
            "msg-fallback",
            "msg-error"
        );

        TurnState state = executor.resumePermission(new PermissionResumeRequest(new PermissionResponse(
            "session-1",
            "turn-1",
            "toolu-1",
            PermissionBehavior.ALLOW,
            Optional.empty(),
            Optional.empty()
        )));

        assertThat(state.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(state.currentToolRound()).isEqualTo(1);
        assertThat(tools.requests).isEmpty();
        assertThat(session.messages().getLast().content().getFirst().text()).contains("工具调用轮数上限 1");
    }

    @Test
    void failsTurnBeforeToolExecutionWhenToolRuntimeCwdDiffersFromAgentRuntimeCwd() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        Path agentCwd = Path.of("/workspace/project-a").toAbsolutePath().normalize();
        Path toolCwd = Path.of("/workspace/project-b").toAbsolutePath().normalize();
        tools.cwd(toolCwd);
        provider.enqueue(List.of(
            new AssistantStart("msg-tool-call"),
            new ToolCallDelta("toolu-1", "read", Map.of("path", "pom.xml"), true),
            new AssistantDone(Optional.empty(), Optional.of("tool_calls"))
        ));
        tools.enqueue(List.of(new ToolResult<>(
            "should not run",
            false,
            List.of(AgentCoreTestFixtures.toolResultMessage("msg-tool-result", "toolu-1", "content", false)),
            Optional.empty()
        )));
        ContextAssembler assembler = request -> new ContextAssembly(
            AgentCoreTestFixtures.minimalContext(session.messages()),
            List.of(),
            List.of(),
            List.of(),
            false
        );
        DefaultTurnExecutor executor = new DefaultTurnExecutor(
            AgentCoreTestFixtures.ports(
                agentCwd,
                session,
                provider,
                tools,
                eventBus,
                assembler,
                new NoopCompactionCoordinator(),
                new NoopMemoryExtractionWorker()
            ),
            TurnIds.fixed("turn-1", "msg-user", "msg-fallback", "msg-error"),
            clock
        );

        TurnState state = executor.execute(new TurnRequest("session-1", "run tools", Optional.empty(), () -> false));

        assertThat(state.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(tools.requests).isEmpty();
        assertThat(session.messages().getLast().content().getFirst().text()).contains("工具运行目录不一致");
    }

    @Test
    void failsTurnWhenMaxToolRoundsIsExceeded() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        AgentCoreTestFixtures.RecordingMemoryExtractionWorker memory = new AgentCoreTestFixtures.RecordingMemoryExtractionWorker();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        provider.enqueue(List.of(
            new AssistantStart("msg-tool-call-1"),
            new ToolCallDelta("toolu-1", "read", Map.of("path", "pom.xml"), true),
            new AssistantDone(Optional.empty(), Optional.of("tool_calls"))
        ));
        provider.enqueue(List.of(
            new AssistantStart("msg-tool-call-2"),
            new ToolCallDelta("toolu-2", "read", Map.of("path", "pom.xml"), true),
            new AssistantDone(Optional.empty(), Optional.of("tool_calls"))
        ));
        tools.enqueue(List.of(new ToolResult<>(
            "ok",
            false,
            List.of(AgentCoreTestFixtures.toolResultMessage("msg-tool-result", "toolu-1", "content", false)),
            Optional.empty()
        )));
        ContextAssembler assembler = request -> new ContextAssembly(
            AgentCoreTestFixtures.minimalContext(session.messages()),
            List.of(),
            List.of(),
            List.of(),
            false
        );
        DefaultTurnExecutor executor = new DefaultTurnExecutor(
            AgentCoreTestFixtures.ports(
                session,
                provider,
                tools,
                eventBus,
                assembler,
                new NoopCompactionCoordinator(),
                memory
            ),
            TurnIds.fixed("turn-1", "msg-user", "msg-fallback-1", "msg-fallback-2", "msg-error"),
            clock
        );

        TurnState state = executor.execute(new TurnRequest("session-1", "run tools", Optional.empty(), () -> false, 1));

        assertThat(state.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(state.currentToolRound()).isEqualTo(1);
        assertThat(tools.requests).hasSize(1);
        assertThat(session.messages()).extracting(AgentMessage::id)
            .containsExactly("msg-user", "msg-tool-call-1", "msg-tool-result", "msg-tool-call-2", "msg-error");
        assertThat(session.messages().getLast().kind()).isEqualTo(cn.lypi.contracts.context.MessageKind.ERROR);
        assertThat(session.messages().getLast().content().getFirst().text()).contains("工具调用轮数上限");
        assertThat(eventBus.events.stream()
            .filter(event -> event instanceof MessageStartEvent || event instanceof MessageEndEvent)
            .map(event -> event.getClass().getSimpleName() + ":" + messageId(event))
            .toList())
            .containsExactly(
                "MessageStartEvent:msg-user",
                "MessageEndEvent:msg-user",
                "MessageStartEvent:msg-tool-call-1",
                "MessageEndEvent:msg-tool-call-1",
                "MessageStartEvent:msg-tool-result",
                "MessageEndEvent:msg-tool-result",
                "MessageStartEvent:msg-tool-call-2",
                "MessageEndEvent:msg-tool-call-2",
                "MessageStartEvent:msg-error",
                "MessageEndEvent:msg-error"
            );
        assertThat(memory.calls).isZero();
    }

    @Test
    void preservesMultipleToolCallRequestResultAndEventOrder() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        provider.enqueue(List.of(
            new AssistantStart("msg-tool-call"),
            new ToolCallDelta("toolu-1", "read", Map.of("path", "pom.xml"), true),
            new ToolCallDelta("toolu-2", "grep", Map.of("pattern", "agent"), true),
            new AssistantDone(Optional.empty(), Optional.of("tool_calls"))
        ));
        provider.enqueue(List.of(
            new AssistantStart("msg-final"),
            new TextDelta("done"),
            new AssistantDone(Optional.empty(), Optional.of("end_turn"))
        ));
        tools.enqueue(List.of(new ToolResult<>(
            "first",
            false,
            List.of(AgentCoreTestFixtures.toolResultMessage("msg-tool-result-1", "toolu-1", "first", false)),
            Optional.empty()
        )));
        tools.enqueue(List.of(new ToolResult<>(
            "second",
            false,
            List.of(AgentCoreTestFixtures.toolResultMessage("msg-tool-result-2", "toolu-2", "second", false)),
            Optional.empty()
        )));
        ContextAssembler assembler = request -> new ContextAssembly(
            AgentCoreTestFixtures.minimalContext(session.messages()),
            List.of(),
            List.of(),
            List.of(),
            false
        );
        DefaultTurnExecutor executor = new DefaultTurnExecutor(
            AgentCoreTestFixtures.ports(
                session,
                provider,
                tools,
                eventBus,
                assembler,
                new NoopCompactionCoordinator(),
                new NoopMemoryExtractionWorker()
            ),
            TurnIds.fixed("turn-1", "msg-user", "msg-fallback-1", "msg-fallback-2"),
            clock
        );

        executor.execute(new TurnRequest("session-1", "run tools", Optional.empty(), () -> false));

        assertThat(tools.requests).extracting(requests -> requests.getFirst().toolUseId())
            .containsExactly("toolu-1", "toolu-2");
        assertThat(session.messages()).extracting(AgentMessage::id)
            .containsExactly("msg-user", "msg-tool-call", "msg-tool-result-1", "msg-tool-result-2", "msg-final");
        assertThat(eventBus.events.stream()
            .filter(event -> event instanceof ToolStartEvent || event instanceof ToolEndEvent)
            .map(event -> event.getClass().getSimpleName() + ":" + toolUseId(event))
            .toList())
            .containsExactly(
                "ToolStartEvent:toolu-1",
                "ToolEndEvent:toolu-1",
                "ToolStartEvent:toolu-2",
                "ToolEndEvent:toolu-2"
            );
    }

    @Test
    void waitsForPermissionAfterPersistingEarlierToolResultsInSameAssistantMessage() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        provider.enqueue(List.of(
            new AssistantStart("msg-tool-call"),
            new ToolCallDelta("toolu-1", "read", Map.of("path", "README.md"), true),
            new ToolCallDelta("toolu-2", "write", Map.of("path", "README.md"), true),
            new AssistantDone(Optional.empty(), Optional.of("tool_calls"))
        ));
        tools.enqueue(List.of(new ToolResult<>(
            "read",
            false,
            List.of(AgentCoreTestFixtures.toolResultMessage("msg-tool-result-1", "toolu-1", "read", false)),
            Optional.empty()
        )));
        tools.failWith(new PendingToolPermissionException(pendingPermission("turn-1", "toolu-2")));
        DefaultTurnExecutor executor = executor(
            session,
            provider,
            tools,
            eventBus,
            clock,
            "turn-1",
            "msg-user",
            "msg-fallback",
            "entry-pending",
            "msg-error"
        );

        TurnState state = executor.execute(new TurnRequest("session-1", "run tools", Optional.empty(), () -> false));

        assertThat(state.status()).isEqualTo(TurnStatus.WAITING_PERMISSION);
        assertThat(session.messages()).extracting(AgentMessage::id)
            .containsExactly("msg-user", "msg-tool-call", "msg-tool-result-1");
        assertThat(session.branch(session.leafId())).extracting(SessionEntry::id)
            .containsExactly("entry-msg-user", "entry-msg-tool-call", "entry-msg-tool-result-1", "entry-pending");
        assertThat(tools.requests).extracting(requests -> requests.getFirst().toolUseId())
            .containsExactly("toolu-1", "toolu-2");
        assertThat(eventBus.events.stream()
            .filter(event -> event instanceof ToolStartEvent || event instanceof ToolEndEvent)
            .map(event -> event.getClass().getSimpleName() + ":" + toolUseId(event))
            .toList())
            .containsExactly(
                "ToolStartEvent:toolu-1",
                "ToolEndEvent:toolu-1",
                "ToolStartEvent:toolu-2"
            );
    }

    @Test
    void failsTurnWhenAssistantToolCallIsIncomplete() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        AgentCoreTestFixtures.RecordingMemoryExtractionWorker memory = new AgentCoreTestFixtures.RecordingMemoryExtractionWorker();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        provider.enqueue(List.of(
            new AssistantStart("msg-tool-call"),
            new ToolCallDelta("toolu-1", "read", Map.of(), false),
            new AssistantDone(Optional.empty(), Optional.of("tool_calls"))
        ));
        ContextAssembler assembler = request -> new ContextAssembly(
            AgentCoreTestFixtures.minimalContext(session.messages()),
            List.of(),
            List.of(),
            List.of(),
            false
        );
        DefaultTurnExecutor executor = new DefaultTurnExecutor(
            AgentCoreTestFixtures.ports(
                session,
                provider,
                tools,
                eventBus,
                assembler,
                new NoopCompactionCoordinator(),
                memory
            ),
            TurnIds.fixed("turn-1", "msg-user", "msg-fallback", "msg-error", "msg-catch"),
            clock
        );

        TurnState state = executor.execute(new TurnRequest("session-1", "run tools", Optional.empty(), () -> false));

        assertThat(state.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(tools.requests).isEmpty();
        assertThat(session.messages()).extracting(AgentMessage::id)
            .containsExactly("msg-user", "msg-tool-call", "msg-error");
        assertThat(session.messages().getLast().kind()).isEqualTo(cn.lypi.contracts.context.MessageKind.ERROR);
        assertThat(session.messages().getLast().content().getFirst().text()).contains("工具调用参数未完成");
        assertThat(eventBus.events.stream()
            .filter(event -> event instanceof MessageStartEvent || event instanceof MessageEndEvent)
            .map(event -> event.getClass().getSimpleName() + ":" + messageId(event))
            .toList())
            .containsExactly(
                "MessageStartEvent:msg-user",
                "MessageEndEvent:msg-user",
                "MessageStartEvent:msg-tool-call",
                "MessageEndEvent:msg-tool-call",
                "MessageStartEvent:msg-error",
                "MessageEndEvent:msg-error"
            );
        assertThat(memory.calls).isZero();
    }

    @Test
    void publishesToolEndErrorEventsWhenToolRuntimeThrows() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        provider.enqueue(List.of(
            new AssistantStart("msg-tool-call"),
            new ToolCallDelta("toolu-1", "read", Map.of("path", "pom.xml"), true),
            new ToolCallDelta("toolu-2", "grep", Map.of("pattern", "agent"), true),
            new AssistantDone(Optional.empty(), Optional.of("tool_calls"))
        ));
        tools.failWith(new RuntimeException("tool runtime down"));
        ContextAssembler assembler = request -> new ContextAssembly(
            AgentCoreTestFixtures.minimalContext(session.messages()),
            List.of(),
            List.of(),
            List.of(),
            false
        );
        DefaultTurnExecutor executor = new DefaultTurnExecutor(
            AgentCoreTestFixtures.ports(
                session,
                provider,
                tools,
                eventBus,
                assembler,
                new NoopCompactionCoordinator(),
                new NoopMemoryExtractionWorker()
            ),
            TurnIds.fixed("turn-1", "msg-user", "msg-fallback", "msg-error"),
            clock
        );

        TurnState state = executor.execute(new TurnRequest("session-1", "run tools", Optional.empty(), () -> false));

        assertThat(state.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(tools.requests.getFirst()).extracting(request -> request.toolUseId())
            .containsExactly("toolu-1");
        assertThat(eventBus.events.stream()
            .filter(event -> event instanceof ToolStartEvent || event instanceof ToolEndEvent)
            .map(event -> event.getClass().getSimpleName() + ":" + toolUseId(event) + ":" + toolError(event))
            .toList())
            .containsExactly(
                "ToolStartEvent:toolu-1:false",
                "ToolEndEvent:toolu-1:true"
            );
    }

    @Test
    void failsTurnAndClosesToolEventsWhenToolRuntimeReturnsTooFewResults() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        provider.enqueue(List.of(
            new AssistantStart("msg-tool-call"),
            new ToolCallDelta("toolu-1", "read", Map.of("path", "pom.xml"), true),
            new AssistantDone(Optional.empty(), Optional.of("tool_calls"))
        ));
        tools.enqueue(List.of());
        ContextAssembler assembler = request -> new ContextAssembly(
            AgentCoreTestFixtures.minimalContext(session.messages()),
            List.of(),
            List.of(),
            List.of(),
            false
        );
        DefaultTurnExecutor executor = new DefaultTurnExecutor(
            AgentCoreTestFixtures.ports(
                session,
                provider,
                tools,
                eventBus,
                assembler,
                new NoopCompactionCoordinator(),
                new NoopMemoryExtractionWorker()
            ),
            TurnIds.fixed("turn-1", "msg-user", "msg-fallback", "msg-error"),
            clock
        );

        TurnState state = executor.execute(new TurnRequest("session-1", "run tools", Optional.empty(), () -> false));

        assertThat(state.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(provider.contexts).hasSize(1);
        assertThat(eventBus.events.stream()
            .filter(event -> event instanceof ToolStartEvent || event instanceof ToolEndEvent)
            .map(event -> event.getClass().getSimpleName() + ":" + toolUseId(event) + ":" + toolError(event))
            .toList())
            .containsExactly(
                "ToolStartEvent:toolu-1:false",
                "ToolEndEvent:toolu-1:true"
            );
    }

    @Test
    void failsTurnAndClosesToolEventsWhenToolRuntimeReturnsTooManyResults() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        provider.enqueue(List.of(
            new AssistantStart("msg-tool-call"),
            new ToolCallDelta("toolu-1", "read", Map.of("path", "pom.xml"), true),
            new AssistantDone(Optional.empty(), Optional.of("tool_calls"))
        ));
        tools.enqueue(List.of(
            new ToolResult<>(
                "first",
                false,
                List.of(AgentCoreTestFixtures.toolResultMessage("msg-tool-result-1", "toolu-1", "first", false)),
                Optional.empty()
            ),
            new ToolResult<>(
                "unexpected",
                false,
                List.of(AgentCoreTestFixtures.toolResultMessage("msg-tool-result-2", "toolu-extra", "extra", false)),
                Optional.empty()
            )
        ));
        ContextAssembler assembler = request -> new ContextAssembly(
            AgentCoreTestFixtures.minimalContext(session.messages()),
            List.of(),
            List.of(),
            List.of(),
            false
        );
        DefaultTurnExecutor executor = new DefaultTurnExecutor(
            AgentCoreTestFixtures.ports(
                session,
                provider,
                tools,
                eventBus,
                assembler,
                new NoopCompactionCoordinator(),
                new NoopMemoryExtractionWorker()
            ),
            TurnIds.fixed("turn-1", "msg-user", "msg-fallback", "msg-error"),
            clock
        );

        TurnState state = executor.execute(new TurnRequest("session-1", "run tools", Optional.empty(), () -> false));

        assertThat(state.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(provider.contexts).hasSize(1);
        assertThat(eventBus.events.stream()
            .filter(event -> event instanceof ToolStartEvent || event instanceof ToolEndEvent)
            .map(event -> event.getClass().getSimpleName() + ":" + toolUseId(event) + ":" + toolError(event))
            .toList())
            .containsExactly(
                "ToolStartEvent:toolu-1:false",
                "ToolEndEvent:toolu-1:true"
            );
    }

    @Test
    void runsCompactionPreflightBeforeModelCall() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        provider.enqueue(List.of(
            new AssistantStart("msg-assistant"),
            new TextDelta("hi"),
            new AssistantDone(Optional.empty(), Optional.of("end_turn"))
        ));
        ContextSnapshot originalContext = AgentCoreTestFixtures.minimalContext(List.of());
        ContextSnapshot compactedContext = AgentCoreTestFixtures.minimalContext(List.of(
            AgentCoreTestFixtures.summaryMessage("summary-1", "summary")
        ));
        ContextAssembler assembler = request -> new ContextAssembly(
            originalContext,
            List.of("entry-msg-user"),
            List.of(),
            List.of(),
            true
        );
        AtomicReference<cn.lypi.agent.compact.CompactionRequest> compactionRequest = new AtomicReference<>();
        CompactionCoordinator compaction = request -> {
            compactionRequest.set(request);
            return new CompactionDecision(
                compactedContext,
                Optional.empty(),
                true,
                "test compacted"
            );
        };
        DefaultTurnExecutor executor = new DefaultTurnExecutor(
            AgentCoreTestFixtures.ports(
                session,
                provider,
                tools,
                eventBus,
                assembler,
                compaction,
                new NoopMemoryExtractionWorker()
            ),
            TurnIds.fixed("turn-1", "msg-user", "msg-fallback"),
            clock
        );

        executor.execute(new TurnRequest("session-1", "hello", Optional.empty(), () -> false));

        assertThat(compactionRequest.get().sessionId()).isEqualTo("session-1");
        assertThat(compactionRequest.get().leafEntryId()).contains("entry-msg-user");
        assertThat(compactionRequest.get().contextBuildRequest().includeSystemPrompt()).isTrue();
        assertThat(compactionRequest.get().assembly().snapshot()).isSameAs(originalContext);
        assertThat(provider.contexts).containsExactly(compactedContext);
    }

    @Test
    void buildsContextWithRuntimeCwd() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        Path runtimeCwd = Path.of("/workspace/project").toAbsolutePath().normalize();
        List<Path> requestedCwds = new ArrayList<>();
        provider.enqueue(List.of(
            new AssistantStart("msg-assistant"),
            new TextDelta("hi"),
            new AssistantDone(Optional.empty(), Optional.of("end_turn"))
        ));
        ContextAssembler assembler = request -> {
            requestedCwds.add(request.cwd());
            return new ContextAssembly(
                AgentCoreTestFixtures.minimalContext(session.messages()),
                List.of(),
                List.of(),
                List.of(),
                false
            );
        };
        DefaultTurnExecutor executor = new DefaultTurnExecutor(
            AgentCoreTestFixtures.ports(
                runtimeCwd,
                session,
                provider,
                tools,
                eventBus,
                assembler,
                new NoopCompactionCoordinator(),
                new NoopMemoryExtractionWorker()
            ),
            TurnIds.fixed("turn-1", "msg-user", "msg-fallback"),
            clock
        );

        executor.execute(new TurnRequest("session-1", "hello", Optional.empty(), () -> false));

        assertThat(requestedCwds).containsExactly(runtimeCwd);
    }

    @Test
    void abortsAndPersistsPartialAssistant() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        AgentCoreTestFixtures.RecordingMemoryExtractionWorker memory = new AgentCoreTestFixtures.RecordingMemoryExtractionWorker();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AtomicBoolean aborted = new AtomicBoolean();
        provider.enqueue(List.of(
            new AssistantStart("msg-partial"),
            new TextDelta("partial"),
            new TextDelta("ignored")
        ));

        ContextAssembler assembler = request -> new ContextAssembly(
            AgentCoreTestFixtures.minimalContext(session.messages()),
            List.of(),
            List.of(),
            List.of(),
            false
        );
        DefaultTurnExecutor executor = new DefaultTurnExecutor(
            AgentCoreTestFixtures.ports(
                session,
                provider,
                tools,
                eventBus,
                assembler,
                new NoopCompactionCoordinator(),
                memory
            ),
            TurnIds.fixed("turn-1", "msg-user", "msg-fallback"),
            clock
        );

        TurnState state = executor.execute(new TurnRequest(
            "session-1",
            "hello",
            Optional.empty(),
            () -> aborted.getAndSet(true)
        ));

        assertThat(state.status()).isEqualTo(TurnStatus.ABORTED);
        assertThat(session.messages()).extracting(AgentMessage::id)
            .containsExactly("msg-user", "msg-partial");
        assertThat(session.messages().getLast().content().getFirst().text()).isEqualTo("partial");
        assertThat(session.messages().getLast().stopReason()).contains("aborted");
        assertThat(memory.calls).isZero();
    }

    @Test
    void runsMemoryExtractionAfterCompletedTurn() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        AgentCoreTestFixtures.RecordingMemoryExtractionWorker memory = new AgentCoreTestFixtures.RecordingMemoryExtractionWorker();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        provider.enqueue(List.of(
            new AssistantStart("msg-assistant"),
            new TextDelta("hi"),
            new AssistantDone(Optional.empty(), Optional.of("end_turn"))
        ));
        ContextAssembler assembler = request -> new ContextAssembly(
            AgentCoreTestFixtures.minimalContext(session.messages()),
            List.of(),
            List.of(),
            List.of(),
            false
        );
        DefaultTurnExecutor executor = new DefaultTurnExecutor(
            AgentCoreTestFixtures.ports(
                session,
                provider,
                tools,
                eventBus,
                assembler,
                new NoopCompactionCoordinator(),
                memory
            ),
            TurnIds.fixed("turn-1", "msg-user", "msg-fallback"),
            clock
        );

        TurnState state = executor.execute(new TurnRequest("session-1", "hello", Optional.empty(), () -> false));

        assertThat(state.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(memory.calls).isEqualTo(1);
    }

    @Test
    void failsTurnAndPersistsErrorMessageOnProviderFailure() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        AgentCoreTestFixtures.RecordingMemoryExtractionWorker memory = new AgentCoreTestFixtures.RecordingMemoryExtractionWorker();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        provider.failWith(new RuntimeException("provider down"));
        ContextAssembler assembler = request -> new ContextAssembly(
            AgentCoreTestFixtures.minimalContext(session.messages()),
            List.of(),
            List.of(),
            List.of(),
            false
        );
        DefaultTurnExecutor executor = new DefaultTurnExecutor(
            AgentCoreTestFixtures.ports(
                session,
                provider,
                tools,
                eventBus,
                assembler,
                new NoopCompactionCoordinator(),
                memory
            ),
            TurnIds.fixed("turn-1", "msg-user", "msg-error"),
            clock
        );

        TurnState state = executor.execute(new TurnRequest("session-1", "hello", Optional.empty(), () -> false));

        assertThat(state.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(session.messages()).extracting(AgentMessage::id)
            .containsExactly("msg-user", "msg-error");
        assertThat(session.messages().getLast().kind()).isEqualTo(cn.lypi.contracts.context.MessageKind.ERROR);
        assertThat(eventBus.events).anySatisfy(event -> assertThat(event).isInstanceOf(ErrorEvent.class));
        assertThat(eventBus.events.stream()
            .filter(event -> event instanceof MessageStartEvent || event instanceof MessageEndEvent)
            .map(event -> event.getClass().getSimpleName() + ":" + messageId(event))
            .toList())
            .containsExactly(
                "MessageStartEvent:msg-user",
                "MessageEndEvent:msg-user",
                "MessageStartEvent:msg-error",
                "MessageEndEvent:msg-error"
            );
        assertThat(((TurnEndEvent) eventBus.events.getLast()).status()).isEqualTo("FAILED");
        assertThat(memory.calls).isZero();
    }

    @Test
    void closesStartedAssistantMessageWhenProviderStreamThrowsDuringIteration() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        AgentCoreTestFixtures.RecordingMemoryExtractionWorker memory = new AgentCoreTestFixtures.RecordingMemoryExtractionWorker();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        provider.enqueueFailingAfter(
            List.of(
                new AssistantStart("msg-assistant"),
                new TextDelta("partial")
            ),
            new RuntimeException("stream interrupted")
        );
        ContextAssembler assembler = request -> new ContextAssembly(
            AgentCoreTestFixtures.minimalContext(session.messages()),
            List.of(),
            List.of(),
            List.of(),
            false
        );
        DefaultTurnExecutor executor = new DefaultTurnExecutor(
            AgentCoreTestFixtures.ports(
                session,
                provider,
                tools,
                eventBus,
                assembler,
                new NoopCompactionCoordinator(),
                memory
            ),
            TurnIds.fixed("turn-1", "msg-user", "msg-error"),
            clock
        );

        TurnState state = executor.execute(new TurnRequest("session-1", "hello", Optional.empty(), () -> false));

        assertThat(state.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(session.messages()).extracting(AgentMessage::id)
            .containsExactly("msg-user", "msg-error");
        assertThat(eventBus.events.stream()
            .filter(event -> event instanceof MessageStartEvent || event instanceof MessageEndEvent)
            .map(event -> event.getClass().getSimpleName() + ":" + messageId(event))
            .toList())
            .containsExactly(
                "MessageStartEvent:msg-user",
                "MessageEndEvent:msg-user",
                "MessageStartEvent:msg-assistant",
                "MessageEndEvent:msg-assistant",
                "MessageStartEvent:msg-error",
                "MessageEndEvent:msg-error"
            );
        assertThat(memory.calls).isZero();
    }

    @Test
    void failsTurnWhenProviderStreamEmitsAssistantError() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        AgentCoreTestFixtures.RecordingMemoryExtractionWorker memory = new AgentCoreTestFixtures.RecordingMemoryExtractionWorker();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        provider.enqueue(List.of(
            new AssistantStart("msg-error"),
            new AssistantError("provider-error", "provider stream failed")
        ));
        ContextAssembler assembler = request -> new ContextAssembly(
            AgentCoreTestFixtures.minimalContext(session.messages()),
            List.of(),
            List.of(),
            List.of(),
            false
        );
        DefaultTurnExecutor executor = new DefaultTurnExecutor(
            AgentCoreTestFixtures.ports(
                session,
                provider,
                tools,
                eventBus,
                assembler,
                new NoopCompactionCoordinator(),
                memory
            ),
            TurnIds.fixed("turn-1", "msg-user", "msg-fallback"),
            clock
        );

        TurnState state = executor.execute(new TurnRequest("session-1", "hello", Optional.empty(), () -> false));

        assertThat(state.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(session.messages()).extracting(AgentMessage::id)
            .containsExactly("msg-user", "msg-error");
        assertThat(session.messages().getLast().kind()).isEqualTo(cn.lypi.contracts.context.MessageKind.ERROR);
        assertThat(((TurnEndEvent) eventBus.events.getLast()).status()).isEqualTo("FAILED");
        assertThat(memory.calls).isZero();
    }

    @Test
    void failsTurnWhenProviderStreamEmitsToolCallAndAssistantError() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        AgentCoreTestFixtures.RecordingMemoryExtractionWorker memory = new AgentCoreTestFixtures.RecordingMemoryExtractionWorker();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        provider.enqueue(List.of(
            new AssistantStart("msg-error"),
            new ToolCallDelta("toolu-1", "read", Map.of("path", "pom.xml"), true),
            new AssistantError("provider-error", "provider stream failed")
        ));
        ContextAssembler assembler = request -> new ContextAssembly(
            AgentCoreTestFixtures.minimalContext(session.messages()),
            List.of(),
            List.of(),
            List.of(),
            false
        );
        DefaultTurnExecutor executor = new DefaultTurnExecutor(
            AgentCoreTestFixtures.ports(
                session,
                provider,
                tools,
                eventBus,
                assembler,
                new NoopCompactionCoordinator(),
                memory
            ),
            TurnIds.fixed("turn-1", "msg-user", "msg-fallback"),
            clock
        );

        TurnState state = executor.execute(new TurnRequest("session-1", "hello", Optional.empty(), () -> false));

        assertThat(state.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(tools.requests).isEmpty();
        assertThat(session.messages()).extracting(AgentMessage::id)
            .containsExactly("msg-user", "msg-error");
        assertThat(eventBus.events.stream()
            .filter(MessageEndEvent.class::isInstance)
            .map(MessageEndEvent.class::cast)
            .map(MessageEndEvent::messageId)
            .toList())
            .containsExactly("msg-user", "msg-error");
        assertThat(((TurnEndEvent) eventBus.events.getLast()).status()).isEqualTo("FAILED");
        assertThat(memory.calls).isZero();
    }

    @Test
    void appendsTurnMessagesUnderRequestedParentEntryId() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        session.openOrCreate("session-1");
        session.appendMessage(AgentCoreTestFixtures.userMessage("msg-root", "root"));
        String requestedParent = session.leafId();
        session.appendMessage(AgentCoreTestFixtures.assistantMessage("msg-other", "other branch"));
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        provider.enqueue(List.of(
            new AssistantStart("msg-assistant"),
            new TextDelta("hi"),
            new AssistantDone(Optional.empty(), Optional.of("end_turn"))
        ));
        ContextAssembler assembler = request -> new ContextAssembly(
            AgentCoreTestFixtures.minimalContext(session.messages()),
            List.of(),
            List.of(),
            List.of(),
            false
        );
        DefaultTurnExecutor executor = new DefaultTurnExecutor(
            AgentCoreTestFixtures.ports(
                session,
                provider,
                tools,
                eventBus,
                assembler,
                new NoopCompactionCoordinator(),
                new NoopMemoryExtractionWorker()
            ),
            TurnIds.fixed("turn-1", "msg-user", "msg-fallback"),
            clock
        );

        executor.execute(new TurnRequest("session-1", "hello", Optional.of(requestedParent), () -> false));

        MessageEntry userEntry = (MessageEntry) session.entry("entry-msg-user");
        MessageEntry assistantEntry = (MessageEntry) session.entry("entry-msg-assistant");
        assertThat(userEntry.parentId()).isEqualTo(requestedParent);
        assertThat(assistantEntry.parentId()).isEqualTo("entry-msg-user");
    }

    @Test
    void buildsFirstModelContextFromNewBranchLeafWhenParentEntryIdProvided() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        session.openOrCreate("session-1");
        session.appendMessage(AgentCoreTestFixtures.userMessage("msg-root", "root"));
        String requestedParent = session.leafId();
        session.appendMessage(AgentCoreTestFixtures.assistantMessage("msg-other", "other branch"));
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        provider.enqueue(List.of(
            new AssistantStart("msg-assistant"),
            new TextDelta("hi"),
            new AssistantDone(Optional.empty(), Optional.of("end_turn"))
        ));
        DefaultContextAssembler assembler = new DefaultContextAssembler(
            session,
            AgentCoreTestFixtures.fixedResourceRuntime("system"),
            new ContextBudgetEstimator()
        );
        DefaultTurnExecutor executor = new DefaultTurnExecutor(
            AgentCoreTestFixtures.ports(
                session,
                provider,
                tools,
                eventBus,
                assembler,
                new NoopCompactionCoordinator(),
                new NoopMemoryExtractionWorker()
            ),
            TurnIds.fixed("turn-1", "msg-user", "msg-fallback"),
            clock
        );

        executor.execute(new TurnRequest("session-1", "hello", Optional.of(requestedParent), () -> false));

        assertThat(provider.contexts).hasSize(1);
        assertThat(provider.contexts.getFirst().messages()).extracting(AgentMessage::id)
            .containsExactly("msg-root", "msg-user");
    }

    @Test
    void memoryExtractionFailureDoesNotChangeCompletedTurnStatus() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        AgentCoreTestFixtures.RecordingMemoryExtractionWorker memory = new AgentCoreTestFixtures.RecordingMemoryExtractionWorker();
        memory.failure = new RuntimeException("memory failed");
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        provider.enqueue(List.of(
            new AssistantStart("msg-assistant"),
            new TextDelta("hi"),
            new AssistantDone(Optional.empty(), Optional.of("end_turn"))
        ));
        ContextAssembler assembler = request -> new ContextAssembly(
            AgentCoreTestFixtures.minimalContext(session.messages()),
            List.of(),
            List.of(),
            List.of(),
            false
        );
        DefaultTurnExecutor executor = new DefaultTurnExecutor(
            AgentCoreTestFixtures.ports(
                session,
                provider,
                tools,
                eventBus,
                assembler,
                new NoopCompactionCoordinator(),
                memory
            ),
            TurnIds.fixed("turn-1", "msg-user", "msg-fallback"),
            clock
        );

        TurnState state = executor.execute(new TurnRequest("session-1", "hello", Optional.empty(), () -> false));

        assertThat(state.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(memory.calls).isEqualTo(1);
        assertThat(((TurnEndEvent) eventBus.events.getLast()).status()).isEqualTo("COMPLETED");
    }

    private String toolUseId(AgentEvent event) {
        if (event instanceof ToolStartEvent toolStart) {
            return toolStart.toolUseId();
        }
        if (event instanceof ToolEndEvent toolEnd) {
            return toolEnd.toolUseId();
        }
        return "";
    }

    private boolean toolError(AgentEvent event) {
        if (event instanceof ToolEndEvent toolEnd) {
            return toolEnd.error();
        }
        return false;
    }

    private String messageId(AgentEvent event) {
        if (event instanceof MessageStartEvent messageStart) {
            return messageStart.messageId();
        }
        if (event instanceof MessageEndEvent messageEnd) {
            return messageEnd.messageId();
        }
        return "";
    }

    private DefaultTurnExecutor executor(
        AgentCoreTestFixtures.InMemorySessionManager session,
        AgentCoreTestFixtures.StubAiProvider provider,
        AgentCoreTestFixtures.StubToolRuntime tools,
        AgentCoreTestFixtures.RecordingEventBus eventBus,
        Clock clock,
        String... ids
    ) {
        ContextAssembler assembler = request -> new ContextAssembly(
            AgentCoreTestFixtures.minimalContext(session.messages()),
            List.of(),
            List.of(),
            List.of(),
            false
        );
        return new DefaultTurnExecutor(
            AgentCoreTestFixtures.ports(
                session,
                provider,
                tools,
                eventBus,
                assembler,
                new NoopCompactionCoordinator(),
                new NoopMemoryExtractionWorker()
            ),
            TurnIds.fixed(ids),
            clock
        );
    }

    private PendingPermission pendingPermission(String turnId, String toolUseId) {
        ToolUseRequest request = new ToolUseRequest(toolUseId, "write", Map.of("path", "README.md"), "msg-tool-call");
        return new PendingPermission(
            turnId,
            toolUseId,
            "write",
            "write {path=README.md}",
            "write needs approval",
            new PermissionDecision(
                PermissionBehavior.ASK,
                PermissionDecisionReason.TOOL_SPECIFIC,
                "write needs approval",
                Optional.empty(),
                Map.of()
            ),
            request
        );
    }

    private AgentCoreTestFixtures.InMemorySessionManager sessionWaitingForPermission() {
        return sessionWaitingForPermission(1, TurnRequest.DEFAULT_MAX_TOOL_ROUNDS);
    }

    private AgentCoreTestFixtures.InMemorySessionManager sessionWaitingForPermission(int currentToolRound, int maxToolRounds) {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        session.openOrCreate("session-1");
        AgentMessage assistant = new AgentMessage(
            "msg-tool-call",
            MessageRole.ASSISTANT,
            MessageKind.TOOL_CALL,
            List.of(new ToolCallContentBlock(
                "toolu-1",
                "write",
                "{\"path\":\"README.md\"}",
                Map.of("input", Map.of("path", "README.md"), "complete", true)
            )),
            NOW,
            Optional.empty(),
            Optional.of("tool_calls")
        );
        session.appendMessage(assistant);
        session.append(new PermissionPendingEntry(
            "entry-pending",
            session.leafId(),
            pendingPermission("turn-1", "toolu-1"),
            session.leafId(),
            currentToolRound,
            maxToolRounds,
            NOW
        ));
        return session;
    }

    private ToolResult<?> toolResult(String toolUseId, String text, boolean error) {
        return new ToolResult<>(
            text,
            error,
            List.of(AgentCoreTestFixtures.toolResultMessage("msg-" + toolUseId, toolUseId, text, error)),
            Optional.empty()
        );
    }
}
