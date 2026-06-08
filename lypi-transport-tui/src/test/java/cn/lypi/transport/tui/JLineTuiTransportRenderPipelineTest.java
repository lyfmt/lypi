package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.lypi.contracts.context.ContentBlockKind;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.EventConsumer;
import cn.lypi.contracts.event.EventEnvelope;
import cn.lypi.contracts.event.EventFilter;
import cn.lypi.contracts.event.EventSubscription;
import cn.lypi.contracts.event.MessageDeltaEvent;
import cn.lypi.contracts.tui.SessionRuntimeState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class JLineTuiTransportRenderPipelineTest {
    @Test
    void eventCallbackReducesAndRendersViewModelUnderUiLock() {
        RecordingEventBus events = new RecordingEventBus();
        List<String> frames = new ArrayList<>();
        JLineTuiTransport transport = JLineTuiTransport.withRenderer(lines -> frames.add(String.join("\n", lines)), 40, 4);

        transport.attach(events, TestRuntimeStates.basic("ses_1"));
        events.emit(new MessageDeltaEvent(
            "ses_1",
            "msg_1",
            MessageRole.ASSISTANT,
            MessageKind.TEXT,
            "block_1",
            ContentBlockKind.TEXT,
            "## Done ##",
            true,
            java.util.Map.of(),
            Instant.parse("2026-06-09T00:00:00Z")
        ));

        assertEquals(1, frames.size());
        assertEquals("Done", frames.getFirst().lines().findFirst().orElseThrow());
        assertEquals(1, transport.uiLockEntryCountForTest());
    }

    private static final class RecordingEventBus implements EventBus {
        private EventConsumer consumer;

        @Override
        public void publish(AgentEvent event) {
        }

        @Override
        public EventSubscription subscribe(EventFilter filter, EventConsumer consumer) {
            this.consumer = consumer;
            return () -> {
            };
        }

        void emit(AgentEvent event) {
            consumer.accept(new EventEnvelope("evt_1", "ses_1", 1, event));
        }
    }
}
