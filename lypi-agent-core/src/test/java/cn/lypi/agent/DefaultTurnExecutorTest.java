package cn.lypi.agent;

import cn.lypi.contracts.agent.TurnRequest;
import cn.lypi.contracts.agent.TurnState;
import cn.lypi.contracts.agent.TurnStatus;
import cn.lypi.contracts.context.AgentMessage;
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
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
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
}
