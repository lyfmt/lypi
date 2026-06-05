package cn.lypi.agent;

import cn.lypi.contracts.agent.TurnRequest;
import cn.lypi.contracts.agent.TurnState;
import cn.lypi.contracts.agent.TurnStatus;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.MessageDeltaEvent;
import cn.lypi.contracts.event.MessageEndEvent;
import cn.lypi.contracts.event.MessageStartEvent;
import cn.lypi.contracts.event.TurnEndEvent;
import cn.lypi.contracts.event.TurnStartEvent;
import cn.lypi.contracts.model.AssistantDone;
import cn.lypi.contracts.model.AssistantStart;
import cn.lypi.contracts.model.TextDelta;
import cn.lypi.contracts.model.ToolCallDelta;
import cn.lypi.contracts.tool.ToolResult;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static cn.lypi.agent.AgentCoreTestFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

class DefaultTurnExecutorTest {
    @Test
    void executesSimpleTurnWithoutTools() {
        AgentCoreTestFixtures.InMemorySessionEngine session = new AgentCoreTestFixtures.InMemorySessionEngine();
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
        AgentCoreTestFixtures.InMemorySessionEngine session = new AgentCoreTestFixtures.InMemorySessionEngine();
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
    void runsCompactionPreflightBeforeModelCall() {
        AgentCoreTestFixtures.InMemorySessionEngine session = new AgentCoreTestFixtures.InMemorySessionEngine();
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
            List.of(),
            List.of(),
            List.of(),
            true
        );
        CompactionCoordinator compaction = context -> new CompactionDecision(
            compactedContext,
            Optional.empty(),
            true,
            "test compacted"
        );
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

        assertThat(provider.contexts).containsExactly(compactedContext);
    }
}
