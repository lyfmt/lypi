package cn.lypi.tool;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.common.ToolProgressKind;
import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.EventConsumer;
import cn.lypi.contracts.event.EventFilter;
import cn.lypi.contracts.event.EventSubscription;
import cn.lypi.contracts.event.ToolEndEvent;
import cn.lypi.contracts.event.ToolProgressEvent;
import cn.lypi.contracts.event.ToolStartEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolExecutionEventPublisherTest {
    @Test
    void publishesStartProgressAndEndEvents() {
        RecordingEventBus events = new RecordingEventBus();
        ToolExecutionEventPublisher publisher = ToolExecutionEventPublisher.eventBus(events);

        ProgressSink progress = publisher.start("ses_1", "toolu_1", "bash");
        progress.progress(ToolProgress.status("running command", null));
        publisher.end("ses_1", "toolu_1", false);

        assertEquals(3, events.events.size());
        ToolStartEvent start = assertInstanceOf(ToolStartEvent.class, events.events.get(0));
        assertEquals("ses_1", start.sessionId());
        assertEquals("toolu_1", start.toolUseId());
        assertEquals("bash", start.toolName());

        ToolProgressEvent progressEvent = assertInstanceOf(ToolProgressEvent.class, events.events.get(1));
        assertEquals("ses_1", progressEvent.sessionId());
        assertEquals("toolu_1", progressEvent.toolUseId());
        assertEquals(ToolProgressKind.STATUS, progressEvent.progress().kind());
        assertEquals("running command", progressEvent.progress().title());

        ToolEndEvent end = assertInstanceOf(ToolEndEvent.class, events.events.get(2));
        assertEquals("ses_1", end.sessionId());
        assertEquals("toolu_1", end.toolUseId());
        assertEquals(false, end.error());
    }

    @Test
    void ignoresNullProgress() {
        RecordingEventBus events = new RecordingEventBus();
        ToolExecutionEventPublisher publisher = ToolExecutionEventPublisher.eventBus(events);

        ProgressSink progress = publisher.start("ses_1", "toolu_1", "bash");
        progress.progress(null);
        publisher.end("ses_1", "toolu_1", false);

        assertEquals(2, events.events.size());
        assertInstanceOf(ToolStartEvent.class, events.events.get(0));
        assertInstanceOf(ToolEndEvent.class, events.events.get(1));
    }

    @Test
    void publishFailureDoesNotThrow() {
        ToolExecutionEventPublisher publisher = ToolExecutionEventPublisher.eventBus(new ThrowingEventBus());

        assertDoesNotThrow(() -> {
            ProgressSink progress = publisher.start("ses_1", "toolu_1", "bash");
            progress.progress(ToolProgress.status("running command", null));
            publisher.end("ses_1", "toolu_1", true);
        });
    }

    @Test
    void noopPublisherDoesNothing() {
        ToolExecutionEventPublisher publisher = ToolExecutionEventPublisher.noop();

        assertDoesNotThrow(() -> {
            ProgressSink progress = publisher.start("ses_1", "toolu_1", "bash");
            progress.progress(ToolProgress.status("running command", null));
            publisher.end("ses_1", "toolu_1", false);
        });
    }

    private static final class RecordingEventBus implements EventBus {
        private final List<AgentEvent> events = new ArrayList<>();

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

    private static final class ThrowingEventBus implements EventBus {
        @Override
        public void publish(AgentEvent event) {
            throw new IllegalStateException("event bus unavailable");
        }

        @Override
        public EventSubscription subscribe(EventFilter filter, EventConsumer consumer) {
            return () -> {
            };
        }
    }
}
