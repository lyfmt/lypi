package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.EventConsumer;
import cn.lypi.contracts.event.EventEnvelope;
import cn.lypi.contracts.event.EventFilter;
import cn.lypi.contracts.event.EventSubscription;
import cn.lypi.contracts.event.ErrorEvent;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.tui.SessionRuntimeState;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class JLineTuiTransportTest {
    @Test
    void attachSubscribesToAllEventsAndRendersUnderUiLock() {
        RecordingScreen screen = new RecordingScreen();
        RecordingEventBus events = new RecordingEventBus();
        JLineTuiTransport transport = new JLineTuiTransport(screen::render);

        transport.attach(events, runtimeState());
        events.emit(new ErrorEvent("ses_1", "err_1", "boom", Instant.parse("2026-06-09T00:00:00Z")));

        assertTrue(events.subscribed);
        assertEquals(1, screen.renderCount);
        assertTrue(transport.isUiLockedForTest());
    }

    private SessionRuntimeState runtimeState() {
        return new SessionRuntimeState(
            "ses_1",
            Path.of("."),
            "leaf_1",
            new ModelSelection("openai", "gpt-5.4", ThinkingLevel.HIGH),
            ThinkingLevel.HIGH,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE,
            new ContextBudget(0, 200000, 180000, 12000, 6000, 0, 0, BigDecimal.ZERO),
            false
        );
    }

    @Test
    void nameIdentifiesTuiAdapter() {
        assertEquals("tui", new JLineTuiTransport(() -> {
        }).name());
    }

    private static final class RecordingScreen {
        private int renderCount;

        void render() {
            renderCount++;
        }
    }

    private static final class RecordingEventBus implements EventBus {
        private EventConsumer consumer;
        private boolean subscribed;

        @Override
        public void publish(AgentEvent event) {
        }

        @Override
        public EventSubscription subscribe(EventFilter filter, EventConsumer consumer) {
            assertTrue(filter.sessionId().isEmpty());
            assertTrue(filter.eventType().isEmpty());
            this.consumer = consumer;
            subscribed = true;
            return () -> {
            };
        }

        void emit(AgentEvent event) {
            consumer.accept(new EventEnvelope("evt_1", "ses_1", 1, event));
        }
    }
}
