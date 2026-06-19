package cn.lypi.agent;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.agent.TurnStatus;
import cn.lypi.contracts.context.ContentBlockKind;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.event.MessageBlockSnapshot;
import cn.lypi.contracts.event.MessageEndEvent;
import cn.lypi.contracts.event.MessageStartEvent;
import cn.lypi.contracts.event.TurnEndEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TurnEventPublisherTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void publishesTurnEndWithDurationAndToolRounds() {
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        TurnEventPublisher publisher = new TurnEventPublisher(eventBus, Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC));

        publisher.publishTurnEnd("session-1", "turn-1", TurnStatus.COMPLETED, NOW, 3, "entry-final");

        TurnEndEvent event = (TurnEndEvent) eventBus.events.getFirst();
        assertThat(event.sessionId()).isEqualTo("session-1");
        assertThat(event.turnId()).isEqualTo("turn-1");
        assertThat(event.status()).isEqualTo("COMPLETED");
        assertThat(event.durationMillis()).isEqualTo(2_000L);
        assertThat(event.toolRounds()).isEqualTo(3);
        assertThat(event.timestamp()).isEqualTo(NOW.plusSeconds(2));
        assertThat(event.leafEntryId()).isEqualTo("entry-final");
    }

    @Test
    void snapshotsToolCallMetadataForMessageEnd() {
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        TurnEventPublisher publisher = new TurnEventPublisher(eventBus, Clock.fixed(NOW, ZoneOffset.UTC));

        publisher.publishMessageStart("session-1", AgentCoreTestFixtures.assistantToolCallMessage(
            "msg-assistant",
            "toolu-1",
            "read",
            Map.of("path", "pom.xml")
        ));
        publisher.publishMessageEnd("session-1", AgentCoreTestFixtures.assistantToolCallMessage(
            "msg-assistant",
            "toolu-1",
            "read",
            Map.of("path", "pom.xml")
        ));

        assertThat(eventBus.events.getFirst()).isInstanceOf(MessageStartEvent.class);
        MessageEndEvent end = (MessageEndEvent) eventBus.events.getLast();
        assertThat(end.kind()).isEqualTo(MessageKind.TOOL_CALL);
        MessageBlockSnapshot snapshot = end.blocks().getFirst();
        assertThat(snapshot.blockKind()).isEqualTo(ContentBlockKind.TOOL_CALL);
        assertThat(snapshot.metadata())
            .containsEntry("toolUseId", "toolu-1")
            .containsEntry("toolName", "read")
            .containsEntry("complete", true)
            .containsEntry("inputSummary", "read {path=pom.xml}");
    }
}
