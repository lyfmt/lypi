package cn.lypi.agent;

import cn.lypi.agent.compact.CompactionCoordinator;
import cn.lypi.agent.compact.CompactionDecision;
import cn.lypi.agent.compact.DefaultToolMicroCompactor;
import cn.lypi.agent.compact.NoopCompactionCoordinator;
import cn.lypi.contracts.agent.TurnRequest;
import cn.lypi.contracts.agent.TurnState;
import cn.lypi.contracts.agent.TurnStatus;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlockKind;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.ErrorEvent;
import cn.lypi.contracts.event.MessageDeltaEvent;
import cn.lypi.contracts.event.MessageEndEvent;
import cn.lypi.contracts.event.MessageStartEvent;
import cn.lypi.contracts.event.RetryEndEvent;
import cn.lypi.contracts.event.RetryStartEvent;
import cn.lypi.contracts.event.TurnEndEvent;
import cn.lypi.contracts.event.TurnStartEvent;
import cn.lypi.contracts.event.ToolEndEvent;
import cn.lypi.contracts.event.ToolStartEvent;
import cn.lypi.contracts.model.AssistantDone;
import cn.lypi.contracts.model.AssistantError;
import cn.lypi.contracts.model.AssistantStart;
import cn.lypi.contracts.model.ProviderRetryNotice;
import cn.lypi.contracts.model.TextDelta;
import cn.lypi.contracts.model.ThinkingDelta;
import cn.lypi.contracts.model.ToolCallDelta;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.tool.ToolExecutionStatus;
import cn.lypi.contracts.tool.ToolResult;
import java.nio.charset.StandardCharsets;
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
            AgentCoreTestFixtures.emptyResources(),
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
        MessageStartEvent userStart = (MessageStartEvent) eventBus.events.get(1);
        assertThat(userStart.role()).isEqualTo(MessageRole.USER);
        assertThat(userStart.kind()).isEqualTo(MessageKind.TEXT);
        MessageDeltaEvent assistantDelta = (MessageDeltaEvent) eventBus.events.get(4);
        assertThat(assistantDelta.role()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(assistantDelta.blockKind()).isEqualTo(ContentBlockKind.TEXT);
        assertThat(assistantDelta.blockId()).isEqualTo("msg-assistant:text:0");
        MessageEndEvent assistantEnd = (MessageEndEvent) eventBus.events.get(5);
        assertThat(assistantEnd.role()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(assistantEnd.blocks()).hasSize(1);
        assertThat(assistantEnd.blocks().getFirst().text()).isEqualTo("hi");
        assertThat(assistantEnd.stopReason()).contains("end_turn");
        assertThat(((TurnEndEvent) eventBus.events.getLast()).status()).isEqualTo("COMPLETED");
    }

    @Test
    void mapsProviderRetryNoticeToRetryEventsWithoutAppendingTranscriptNoise() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        provider.enqueue(List.of(
            new ProviderRetryNotice(
                "openai",
                1,
                3,
                java.time.Duration.ofMillis(500),
                "rate_limit",
                "provider.rate_limit",
                "Provider HTTP 429: rate limit"
            ),
            new AssistantStart("msg-assistant"),
            new TextDelta("hi"),
            new AssistantDone(Optional.empty(), Optional.of("end_turn"))
        ));

        ContextAssembler assembler = request -> new ContextAssembly(
            AgentCoreTestFixtures.minimalContext(session.messages()),
            AgentCoreTestFixtures.emptyResources(),
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

        TurnState state = executor.execute(new TurnRequest("session-1", "hello", Optional.empty(), () -> false));

        assertThat(state.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(session.messages()).extracting(AgentMessage::id)
            .containsExactly("msg-user", "msg-assistant");
        assertThat(eventBus.events).anySatisfy(event -> {
            assertThat(event).isInstanceOf(RetryStartEvent.class);
            RetryStartEvent retry = (RetryStartEvent) event;
            assertThat(retry.attempt()).isEqualTo(1);
            assertThat(retry.reason()).isEqualTo("provider.rate_limit");
        });
        assertThat(eventBus.events).anySatisfy(event -> {
            assertThat(event).isInstanceOf(RetryEndEvent.class);
            RetryEndEvent retry = (RetryEndEvent) event;
            assertThat(retry.attempt()).isEqualTo(1);
            assertThat(retry.success()).isTrue();
        });
        assertThat(state.newMessages()).hasSize(2);
    }

    @Test
    void marksProviderRetryEndFailedWhenRetryProducesAssistantError() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        provider.enqueue(List.of(
            new ProviderRetryNotice(
                "openai",
                1,
                1,
                java.time.Duration.ofMillis(500),
                "rate_limit",
                "provider.rate_limit",
                "Provider HTTP 429: rate limit"
            ),
            new AssistantError("provider.request_failed", "Provider request failed.")
        ));

        ContextAssembler assembler = request -> new ContextAssembly(
            AgentCoreTestFixtures.minimalContext(session.messages()),
            AgentCoreTestFixtures.emptyResources(),
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
            TurnIds.fixed("turn-1", "msg-user", "msg-error"),
            clock
        );

        TurnState state = executor.execute(new TurnRequest("session-1", "hello", Optional.empty(), () -> false));

        assertThat(state.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(eventBus.events).anySatisfy(event -> {
            assertThat(event).isInstanceOf(RetryEndEvent.class);
            RetryEndEvent retry = (RetryEndEvent) event;
            assertThat(retry.attempt()).isEqualTo(1);
            assertThat(retry.success()).isFalse();
        });
        assertThat(session.messages()).hasSize(2);
    }

    @Test
    void closesPreviousRetryAttemptWhenProviderEmitsConsecutiveRetryNotices() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        provider.enqueue(List.of(
            new ProviderRetryNotice("openai", 1, 2, java.time.Duration.ofMillis(500), "rate_limit", "provider.rate_limit", "first"),
            new ProviderRetryNotice("openai", 2, 2, java.time.Duration.ofMillis(1_000), "rate_limit", "provider.rate_limit", "second"),
            new AssistantStart("msg-assistant"),
            new TextDelta("hi"),
            new AssistantDone(Optional.empty(), Optional.of("end_turn"))
        ));

        ContextAssembler assembler = request -> new ContextAssembly(
            AgentCoreTestFixtures.minimalContext(session.messages()),
            AgentCoreTestFixtures.emptyResources(),
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

        TurnState state = executor.execute(new TurnRequest("session-1", "hello", Optional.empty(), () -> false));

        assertThat(state.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(eventBus.events.stream()
            .filter(RetryEndEvent.class::isInstance)
            .map(RetryEndEvent.class::cast)
            .map(RetryEndEvent::attempt))
            .containsExactly(1, 2);
        assertThat(eventBus.events.stream()
            .filter(RetryEndEvent.class::isInstance)
            .map(RetryEndEvent.class::cast)
            .map(RetryEndEvent::success))
            .containsExactly(false, true);
    }

    @Test
    void closesPendingRetryWhenStreamEndsWithoutAnotherEvent() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        provider.enqueue(List.of(
            new ProviderRetryNotice("openai", 1, 1, java.time.Duration.ofMillis(500), "rate_limit", "provider.rate_limit", "first")
        ));

        ContextAssembler assembler = request -> new ContextAssembly(
            AgentCoreTestFixtures.minimalContext(session.messages()),
            AgentCoreTestFixtures.emptyResources(),
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

        TurnState state = executor.execute(new TurnRequest("session-1", "hello", Optional.empty(), () -> false));

        assertThat(state.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(eventBus.events.stream()
            .filter(RetryEndEvent.class::isInstance)
            .map(RetryEndEvent.class::cast)
            .map(RetryEndEvent::success))
            .containsExactly(false);
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
            AgentCoreTestFixtures.emptyResources(),
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
        MessageStartEvent toolCallStart = eventBus.events.stream()
            .filter(MessageStartEvent.class::isInstance)
            .map(MessageStartEvent.class::cast)
            .filter(event -> event.messageId().equals("msg-tool-call"))
            .findFirst()
            .orElseThrow();
        MessageEndEvent toolCallEnd = eventBus.events.stream()
            .filter(MessageEndEvent.class::isInstance)
            .map(MessageEndEvent.class::cast)
            .filter(event -> event.messageId().equals("msg-tool-call"))
            .findFirst()
            .orElseThrow();
        assertThat(toolCallStart.kind()).isEqualTo(MessageKind.TOOL_CALL);
        assertThat(toolCallEnd.kind()).isEqualTo(MessageKind.TOOL_CALL);
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
            AgentCoreTestFixtures.emptyResources(),
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
            AgentCoreTestFixtures.emptyResources(),
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
    void preservesMultipleToolCallRequestResultOrderAndPublishesToolLifecycleEvents() {
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
        String rawOutput = "x".repeat(260);
        String budgetedOutput = "[工具结果已超过预算，仅保留预览]";
        tools.enqueue(List.of(
            new ToolResult<>(
                rawOutput,
                false,
                List.of(AgentCoreTestFixtures.toolResultMessage("msg-tool-result-1", "toolu-1", budgetedOutput, false)),
                Optional.empty()
            ),
            new ToolResult<>(
                "second",
                false,
                List.of(AgentCoreTestFixtures.toolResultMessage("msg-tool-result-2", "toolu-2", "second", false)),
                Optional.empty()
            )
        ));
        ContextAssembler assembler = request -> new ContextAssembly(
            AgentCoreTestFixtures.minimalContext(session.messages()),
            AgentCoreTestFixtures.emptyResources(),
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

        assertThat(tools.requests.getFirst()).extracting(request -> request.toolUseId())
            .containsExactly("toolu-1", "toolu-2");
        assertThat(session.messages()).extracting(AgentMessage::id)
            .containsExactly("msg-user", "msg-tool-call", "msg-tool-result-1", "msg-tool-result-2", "msg-final");
        assertThat(eventBus.events.stream()
            .filter(event -> event instanceof ToolStartEvent || event instanceof ToolEndEvent)
            .map(AgentEvent::getClass))
            .containsExactly(ToolStartEvent.class, ToolStartEvent.class, ToolEndEvent.class, ToolEndEvent.class);
        assertThat(eventBus.events.stream()
            .filter(event -> event instanceof ToolStartEvent || event instanceof ToolEndEvent)
            .map(this::toolUseId))
            .containsExactly("toolu-1", "toolu-2", "toolu-1", "toolu-2");
        List<ToolStartEvent> starts = eventBus.events.stream()
            .filter(ToolStartEvent.class::isInstance)
            .map(ToolStartEvent.class::cast)
            .toList();
        assertThat(starts).extracting(ToolStartEvent::parentMessageId)
            .containsExactly("msg-tool-call", "msg-tool-call");
        assertThat(starts).extracting(ToolStartEvent::turnId)
            .containsExactly("turn-1", "turn-1");
        assertThat(starts.getFirst().inputSummary()).isEqualTo("read {path=pom.xml}");
        assertThat(starts.getFirst().inputMetadata()).containsEntry("path", "pom.xml");
        List<ToolEndEvent> ends = eventBus.events.stream()
            .filter(ToolEndEvent.class::isInstance)
            .map(ToolEndEvent.class::cast)
            .toList();
        assertThat(ends).extracting(ToolEndEvent::status)
            .containsExactly(ToolExecutionStatus.SUCCEEDED, ToolExecutionStatus.SUCCEEDED);
        assertThat(ends.getFirst().resultSummary().title()).isEqualTo("read succeeded");
        assertThat(ends.getFirst().resultSummary().summary()).isEqualTo(budgetedOutput);
        assertThat(ends.getFirst().resultSummary().summary()).doesNotContain("x");
        assertThat(ends.getFirst().resultSummary().outputBytes())
            .isEqualTo(budgetedOutput.getBytes(StandardCharsets.UTF_8).length);
        assertThat(ends.getFirst().metadata()).containsEntry("toolName", "read");
        assertThat(ends.getFirst().startedAt()).isEqualTo(NOW);
        assertThat(ends.getFirst().endedAt()).isEqualTo(NOW);
        assertThat(ends.getFirst().durationMillis()).isZero();
    }

    @Test
    void toolLifecycleEventsUseCanonicalToolNameWhenModelRequestsAlias() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        tools.register(AgentCoreTestFixtures.tool("read", List.of("cat")));
        provider.enqueue(List.of(
            new AssistantStart("msg-tool-call"),
            new ToolCallDelta("toolu-1", "cat", Map.of("path", "pom.xml"), true),
            new AssistantDone(Optional.empty(), Optional.of("tool_calls"))
        ));
        provider.enqueue(List.of(
            new AssistantStart("msg-final"),
            new TextDelta("done"),
            new AssistantDone(Optional.empty(), Optional.of("end_turn"))
        ));
        tools.enqueue(List.of(new ToolResult<>(
            "content",
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

        executor.execute(new TurnRequest("session-1", "run alias", Optional.empty(), () -> false));

        ToolStartEvent start = eventBus.events.stream()
            .filter(ToolStartEvent.class::isInstance)
            .map(ToolStartEvent.class::cast)
            .findFirst()
            .orElseThrow();
        ToolEndEvent end = eventBus.events.stream()
            .filter(ToolEndEvent.class::isInstance)
            .map(ToolEndEvent.class::cast)
            .findFirst()
            .orElseThrow();
        assertThat(start.toolName()).isEqualTo("read");
        assertThat(start.displayTitle()).isEqualTo("read");
        assertThat(start.inputSummary()).isEqualTo("read {path=pom.xml}");
        assertThat(start.inputMetadata()).containsEntry("path", "pom.xml");
        assertThat(start.inputMetadata()).containsEntry("originalToolName", "cat");
        assertThat(end.metadata()).containsEntry("toolName", "read");
        assertThat(end.metadata()).containsEntry("originalToolName", "cat");
        assertThat(end.resultSummary().title()).isEqualTo("read succeeded");
        assertThat(end.resultSummary().metadata()).containsEntry("toolName", "read");
        assertThat(end.resultSummary().metadata()).containsEntry("originalToolName", "cat");
    }

    @Test
    void publishesCancelledToolEndWhenToolRuntimeResultCarriesCancelledStatus() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        provider.enqueue(List.of(
            new AssistantStart("msg-tool-call"),
            new ToolCallDelta("toolu-1", "bash", Map.of("command", "sleep 10"), true),
            new AssistantDone(Optional.empty(), Optional.of("tool_calls"))
        ));
        provider.enqueue(List.of(
            new AssistantStart("msg-final"),
            new TextDelta("cancelled"),
            new AssistantDone(Optional.empty(), Optional.of("end_turn"))
        ));
        tools.enqueue(List.of(new ToolResult<>(
            "工具调用已中止。",
            true,
            List.of(AgentCoreTestFixtures.toolResultMessage(
                "msg-tool-result",
                "toolu-1",
                "工具调用已中止。",
                true,
                Map.of("status", ToolExecutionStatus.CANCELLED.name())
            )),
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

        executor.execute(new TurnRequest("session-1", "run tool", Optional.empty(), () -> false));

        ToolEndEvent end = eventBus.events.stream()
            .filter(ToolEndEvent.class::isInstance)
            .map(ToolEndEvent.class::cast)
            .findFirst()
            .orElseThrow();
        assertThat(end.status()).isEqualTo(ToolExecutionStatus.CANCELLED);
        assertThat(end.resultSummary().title()).isEqualTo("bash cancelled");
        assertThat(end.resultSummary().error()).isTrue();
    }

    @Test
    void fallsBackToErrorFlagWhenToolRuntimeResultDoesNotCarryStatusMetadata() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        provider.enqueue(List.of(
            new AssistantStart("msg-tool-call"),
            new ToolCallDelta("toolu-1", "bash", Map.of("command", "exit 1"), true),
            new AssistantDone(Optional.empty(), Optional.of("tool_calls"))
        ));
        provider.enqueue(List.of(
            new AssistantStart("msg-final"),
            new TextDelta("failed"),
            new AssistantDone(Optional.empty(), Optional.of("end_turn"))
        ));
        tools.enqueue(List.of(new ToolResult<>(
            "failed",
            true,
            List.of(AgentCoreTestFixtures.toolResultMessage("msg-tool-result", "toolu-1", "failed", true)),
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

        executor.execute(new TurnRequest("session-1", "run tool", Optional.empty(), () -> false));

        ToolEndEvent end = eventBus.events.stream()
            .filter(ToolEndEvent.class::isInstance)
            .map(ToolEndEvent.class::cast)
            .findFirst()
            .orElseThrow();
        assertThat(end.status()).isEqualTo(ToolExecutionStatus.FAILED);
        assertThat(end.resultSummary().title()).isEqualTo("bash failed");
        assertThat(end.resultSummary().error()).isTrue();
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
            AgentCoreTestFixtures.emptyResources(),
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
    void failsTurnAndPublishesToolLifecycleErrorsWhenToolRuntimeThrows() {
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
            AgentCoreTestFixtures.emptyResources(),
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
            .containsExactly("toolu-1", "toolu-2");
        assertThat(eventBus.events.stream()
            .filter(event -> event instanceof ToolStartEvent || event instanceof ToolEndEvent)
            .map(AgentEvent::getClass))
            .containsExactly(ToolStartEvent.class, ToolStartEvent.class, ToolEndEvent.class, ToolEndEvent.class);
        assertThat(eventBus.events.stream()
            .filter(event -> event instanceof ToolStartEvent || event instanceof ToolEndEvent)
            .map(this::toolUseId))
            .containsExactly("toolu-1", "toolu-2", "toolu-1", "toolu-2");
        assertThat(eventBus.events.stream()
            .filter(ToolEndEvent.class::isInstance)
            .map(this::toolError))
            .containsExactly(true, true);
    }

    @Test
    void failsTurnAndPublishesToolLifecycleErrorsWhenToolRuntimeReturnsTooFewResults() {
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
        tools.enqueue(List.of(new ToolResult<>(
            "only one",
            false,
            List.of(AgentCoreTestFixtures.toolResultMessage("msg-tool-result-1", "toolu-1", "first", false)),
            Optional.empty()
        )));
        ContextAssembler assembler = request -> new ContextAssembly(
            AgentCoreTestFixtures.minimalContext(session.messages()),
            AgentCoreTestFixtures.emptyResources(),
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
            .map(AgentEvent::getClass))
            .containsExactly(ToolStartEvent.class, ToolStartEvent.class, ToolEndEvent.class, ToolEndEvent.class);
        assertThat(eventBus.events.stream()
            .filter(ToolEndEvent.class::isInstance)
            .map(this::toolError))
            .containsExactly(true, true);
    }

    @Test
    void failsTurnAndPublishesToolLifecycleErrorWhenToolRuntimeReturnsTooManyResults() {
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
            AgentCoreTestFixtures.emptyResources(),
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
            .map(AgentEvent::getClass))
            .containsExactly(ToolStartEvent.class, ToolEndEvent.class);
        assertThat(eventBus.events.stream()
            .filter(ToolEndEvent.class::isInstance)
            .map(this::toolError))
            .containsExactly(true);
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
            AgentCoreTestFixtures.emptyResources(),
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
    void sendsMicroCompactedContextWithoutChangingSessionTranscript() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        session.openOrCreate("session-1");
        for (int index = 1; index <= 8; index++) {
            session.appendMessage(toolCallMessage("msg-call-" + index, "read", "tool-" + index));
            session.appendMessage(AgentCoreTestFixtures.toolResultMessage(
                "msg-result-" + index,
                "tool-" + index,
                "result-" + index,
                false
            ));
        }
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
            AgentCoreTestFixtures.emptyResources(),
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
                new DefaultToolMicroCompactor(clock),
                new NoopCompactionCoordinator(),
                new NoopMemoryExtractionWorker()
            ),
            TurnIds.fixed("turn-1", "msg-user", "msg-fallback"),
            clock
        );

        executor.execute(new TurnRequest("session-1", "hello", Optional.empty(), () -> false));

        assertThat(toolResultText(provider.contexts.getFirst(), "tool-1"))
            .isEqualTo(DefaultToolMicroCompactor.CLEARED_TOOL_RESULT_TEXT);
        assertThat(toolResultText(provider.contexts.getFirst(), "tool-8"))
            .isEqualTo("result-8");
        assertThat(session.messages())
            .filteredOn(message -> message.id().equals("msg-result-1"))
            .singleElement()
            .extracting(DefaultTurnExecutorTest::toolResultText)
            .isEqualTo("result-1");
    }

    @Test
    void hotMicroCompactRequestSendsOriginalContextWhenCacheEditIsUnavailable() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        session.openOrCreate("session-1");
        for (int index = 1; index <= 8; index++) {
            session.appendMessage(toolCallMessage("msg-call-" + index, "read", "tool-" + index));
            session.appendMessage(AgentCoreTestFixtures.toolResultMessage(
                "msg-result-" + index,
                "tool-" + index,
                "result-" + index,
                false
            ));
        }
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        MutableTestClock clock = new MutableTestClock(NOW);
        provider.enqueue(List.of(
            new AssistantStart("msg-assistant-1"),
            new TextDelta("hi"),
            new AssistantDone(Optional.empty(), Optional.of("end_turn"))
        ));
        provider.enqueue(List.of(
            new AssistantStart("msg-assistant-2"),
            new TextDelta("again"),
            new AssistantDone(Optional.empty(), Optional.of("end_turn"))
        ));
        DefaultContextAssembler assembler = new DefaultContextAssembler(
            session,
            AgentCoreTestFixtures.fixedResourceRuntime("system"),
            new ContextBudgetEstimator()
        );
        DefaultToolMicroCompactor microCompactor = new DefaultToolMicroCompactor(clock);
        DefaultTurnExecutor executor = new DefaultTurnExecutor(
            AgentCoreTestFixtures.ports(
                session,
                provider,
                tools,
                eventBus,
                assembler,
                microCompactor,
                new NoopCompactionCoordinator(),
                new NoopMemoryExtractionWorker()
            ),
            countingIds(),
            clock
        );

        executor.execute(new TurnRequest("session-1", "hello", Optional.empty(), () -> false));
        clock.advance(java.time.Duration.ofMinutes(1));
        executor.execute(new TurnRequest("session-1", "again", Optional.empty(), () -> false));

        assertThat(toolResultText(provider.contexts.getFirst(), "tool-1"))
            .isEqualTo(DefaultToolMicroCompactor.CLEARED_TOOL_RESULT_TEXT);
        assertThat(toolResultText(provider.contexts.get(1), "tool-1")).isEqualTo("result-1");
    }

    @Test
    void successfulSessionCompactionResetsMicroCompactStateSoNextRequestIsCold() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        session.openOrCreate("session-1");
        for (int index = 1; index <= 8; index++) {
            session.appendMessage(toolCallMessage("msg-call-" + index, "read", "tool-" + index));
            session.appendMessage(AgentCoreTestFixtures.toolResultMessage(
                "msg-result-" + index,
                "tool-" + index,
                "result-" + index,
                false
            ));
        }
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        MutableTestClock clock = new MutableTestClock(NOW);
        provider.enqueue(List.of(
            new AssistantStart("msg-assistant-1"),
            new TextDelta("hi"),
            new AssistantDone(Optional.empty(), Optional.of("end_turn"))
        ));
        provider.enqueue(List.of(
            new AssistantStart("msg-assistant-2"),
            new TextDelta("again"),
            new AssistantDone(Optional.empty(), Optional.of("end_turn"))
        ));
        DefaultContextAssembler assembler = new DefaultContextAssembler(
            session,
            AgentCoreTestFixtures.fixedResourceRuntime("system"),
            new ContextBudgetEstimator()
        );
        java.util.concurrent.atomic.AtomicInteger compactionCalls = new java.util.concurrent.atomic.AtomicInteger();
        CompactionCoordinator compaction = request -> new CompactionDecision(
            request.assembly().snapshot(),
            Optional.empty(),
            compactionCalls.incrementAndGet() == 1,
            "test"
        );
        DefaultTurnExecutor executor = new DefaultTurnExecutor(
            AgentCoreTestFixtures.ports(
                session,
                provider,
                tools,
                eventBus,
                assembler,
                new DefaultToolMicroCompactor(clock),
                compaction,
                new NoopMemoryExtractionWorker()
            ),
            countingIds(),
            clock
        );

        executor.execute(new TurnRequest("session-1", "hello", Optional.empty(), () -> false));
        clock.advance(java.time.Duration.ofMinutes(1));
        executor.execute(new TurnRequest("session-1", "again", Optional.empty(), () -> false));

        assertThat(toolResultText(provider.contexts.get(1), "tool-1"))
            .isEqualTo(DefaultToolMicroCompactor.CLEARED_TOOL_RESULT_TEXT);
    }

    @Test
    void defaultMicroCompactorIsEnabledAndReestimatesBudgetBeforeCompactionPreflight() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        session.openOrCreate("session-1");
        String largeResult = "x".repeat(4_000);
        for (int index = 1; index <= 8; index++) {
            session.appendMessage(toolCallMessage("msg-call-" + index, "read", "tool-" + index));
            session.appendMessage(AgentCoreTestFixtures.toolResultMessage(
                "msg-result-" + index,
                "tool-" + index,
                largeResult,
                false
            ));
        }
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        provider.enqueue(List.of(
            new AssistantStart("msg-assistant"),
            new TextDelta("hi"),
            new AssistantDone(Optional.empty(), Optional.of("end_turn"))
        ));
        ContextSnapshot originalContext = AgentCoreTestFixtures.minimalContext(session.messages());
        ContextAssembler assembler = request -> new ContextAssembly(
            originalContext,
            AgentCoreTestFixtures.emptyResources(),
            session.branch(session.leafId()).stream().map(cn.lypi.contracts.session.SessionEntry::id).toList(),
            List.of(),
            List.of(),
            true
        );
        AtomicReference<ContextSnapshot> preflightContext = new AtomicReference<>();
        CompactionCoordinator compaction = request -> {
            preflightContext.set(request.assembly().snapshot());
            return new CompactionDecision(request.assembly().snapshot(), Optional.empty(), false, "within budget");
        };
        DefaultTurnExecutor executor = new DefaultTurnExecutor(
            new AgentCoreRuntimePorts(
                Path.of("."),
                session,
                provider,
                tools,
                AgentCoreTestFixtures.allowAllSecurityRuntime(),
                AgentCoreTestFixtures.fixedResourceRuntime("system"),
                eventBus,
                assembler,
                null,
                compaction,
                new NoopMemoryExtractionWorker()
            ),
            TurnIds.fixed("turn-1", "msg-user", "msg-fallback"),
            clock
        );

        executor.execute(new TurnRequest("session-1", "hello", Optional.empty(), () -> false));

        assertThat(toolResultText(preflightContext.get(), "tool-1"))
            .isEqualTo(DefaultToolMicroCompactor.CLEARED_TOOL_RESULT_TEXT);
        assertThat(preflightContext.get().budget().estimatedContextTokens())
            .isLessThan(new ContextBudgetEstimator().estimate(originalContext).estimatedContextTokens());
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
                AgentCoreTestFixtures.emptyResources(),
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
            AgentCoreTestFixtures.emptyResources(),
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
            AgentCoreTestFixtures.emptyResources(),
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
            AgentCoreTestFixtures.emptyResources(),
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
            AgentCoreTestFixtures.emptyResources(),
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
        assertThat(messageStartKind(eventBus, "msg-assistant")).isEqualTo(MessageKind.TEXT);
        assertThat(messageEndKind(eventBus, "msg-assistant")).isEqualTo(MessageKind.TEXT);
        assertThat(memory.calls).isZero();
    }

    @Test
    void publishesConsistentTextKindWhenAssistantStreamStartsWithThinkingThenText() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        provider.enqueue(List.of(
            new AssistantStart("msg-assistant"),
            new ThinkingDelta("thinking"),
            new TextDelta("answer"),
            new AssistantDone(Optional.empty(), Optional.of("end_turn"))
        ));
        ContextAssembler assembler = request -> new ContextAssembly(
            AgentCoreTestFixtures.minimalContext(session.messages()),
            AgentCoreTestFixtures.emptyResources(),
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

        TurnState state = executor.execute(new TurnRequest("session-1", "hello", Optional.empty(), () -> false));

        assertThat(state.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(messageStartKind(eventBus, "msg-assistant")).isEqualTo(MessageKind.TEXT);
        assertThat(messageEndKind(eventBus, "msg-assistant")).isEqualTo(MessageKind.TEXT);
        assertThat(messageDeltas(eventBus, "msg-assistant"))
            .extracting(MessageDeltaEvent::blockKind)
            .containsExactly(ContentBlockKind.THINKING, ContentBlockKind.TEXT);
        MessageDeltaEvent thinkingDelta = messageDeltas(eventBus, "msg-assistant").getFirst();
        assertThat(thinkingDelta.kind()).isEqualTo(MessageKind.THINKING);
        assertThat(thinkingDelta.blockId()).isEqualTo("msg-assistant:thinking:0");
        assertThat(thinkingDelta.delta()).isEqualTo("thinking");
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
            AgentCoreTestFixtures.emptyResources(),
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
        assertThat(messageStartKind(eventBus, "msg-error")).isEqualTo(MessageKind.ERROR);
        assertThat(messageEndKind(eventBus, "msg-error")).isEqualTo(MessageKind.ERROR);
        MessageDeltaEvent errorDelta = messageDeltas(eventBus, "msg-error").getFirst();
        assertThat(errorDelta.kind()).isEqualTo(MessageKind.ERROR);
        assertThat(errorDelta.blockKind()).isEqualTo(ContentBlockKind.ERROR);
        assertThat(errorDelta.blockId()).isEqualTo("msg-error:error:0");
        assertThat(errorDelta.delta()).isEqualTo("provider stream failed");
        assertThat(errorDelta.metadata()).containsEntry("errorId", "provider-error");
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
            AgentCoreTestFixtures.emptyResources(),
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
        assertThat(messageStartKind(eventBus, "msg-error")).isEqualTo(MessageKind.ERROR);
        assertThat(messageEndKind(eventBus, "msg-error")).isEqualTo(MessageKind.ERROR);
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
            AgentCoreTestFixtures.emptyResources(),
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
            AgentCoreTestFixtures.emptyResources(),
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

    private static AgentMessage toolCallMessage(String id, String toolName, String toolUseId) {
        return new AgentMessage(
            id,
            MessageRole.ASSISTANT,
            MessageKind.TOOL_CALL,
            List.of(new ToolCallContentBlock(toolUseId, toolName, toolName + " input")),
            NOW,
            Optional.empty(),
            Optional.empty()
        );
    }

    private static String toolResultText(ContextSnapshot context, String toolUseId) {
        return context.messages().stream()
            .filter(message -> message.kind() == MessageKind.TOOL_RESULT)
            .map(message -> (ToolResultContentBlock) message.content().getFirst())
            .filter(block -> block.toolUseId().equals(toolUseId))
            .map(ToolResultContentBlock::text)
            .findFirst()
            .orElseThrow();
    }

    private static String toolResultText(AgentMessage message) {
        return message.content().getFirst().text();
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

    private MessageKind messageStartKind(AgentCoreTestFixtures.RecordingEventBus eventBus, String messageId) {
        return eventBus.events.stream()
            .filter(MessageStartEvent.class::isInstance)
            .map(MessageStartEvent.class::cast)
            .filter(event -> event.messageId().equals(messageId))
            .map(MessageStartEvent::kind)
            .findFirst()
            .orElseThrow();
    }

    private MessageKind messageEndKind(AgentCoreTestFixtures.RecordingEventBus eventBus, String messageId) {
        return eventBus.events.stream()
            .filter(MessageEndEvent.class::isInstance)
            .map(MessageEndEvent.class::cast)
            .filter(event -> event.messageId().equals(messageId))
            .map(MessageEndEvent::kind)
            .findFirst()
            .orElseThrow();
    }

    private List<MessageDeltaEvent> messageDeltas(AgentCoreTestFixtures.RecordingEventBus eventBus, String messageId) {
        return eventBus.events.stream()
            .filter(MessageDeltaEvent.class::isInstance)
            .map(MessageDeltaEvent.class::cast)
            .filter(event -> event.messageId().equals(messageId))
            .toList();
    }

    private static TurnIds countingIds() {
        return new TurnIds() {
            private int index;

            @Override
            public String newTurnId() {
                return next("turn");
            }

            @Override
            public String newMessageId() {
                return next("msg");
            }

            @Override
            public String newEntryId() {
                return next("entry");
            }

            private String next(String prefix) {
                index++;
                return prefix + "-" + index;
            }
        };
    }

    private static final class MutableTestClock extends Clock {
        private java.time.Instant instant;

        private MutableTestClock(java.time.Instant instant) {
            this.instant = instant;
        }

        void advance(java.time.Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public java.time.Instant instant() {
            return instant;
        }
    }
}
