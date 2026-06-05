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
import cn.lypi.contracts.tool.ToolExecutionStatus;
import cn.lypi.contracts.tool.ToolOutputRef;
import cn.lypi.contracts.tool.ToolResultSummary;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolExecutionEventPublisherTest {
    @Test
    void publishesStartProgressAndEndEvents() {
        RecordingEventBus events = new RecordingEventBus();
        ToolExecutionEventPublisher publisher = ToolExecutionEventPublisher.eventBus(events);

        ToolExecutionEventPublisher.StartedToolExecution started = publisher.start(
            "ses_1",
            "toolu_1",
            "msg_1",
            "turn_1",
            "bash",
            "Bash",
            "echo hello",
            Map.of("command", "echo hello")
        );
        ProgressSink progress = started.progressSink();
        progress.progress(ToolProgress.status("running command", null));
        ToolResultSummary summary = new ToolResultSummary(
            "bash succeeded",
            "done",
            false,
            0,
            false,
            4L,
            Map.of("toolName", "bash")
        );
        ToolOutputRef ref = new ToolOutputRef(
            "toolout_1",
            "ses_1",
            "toolu_1",
            "text/plain; charset=utf-8",
            "pending",
            "",
            "sha256:abcd",
            4L,
            Map.of("preview", "done")
        );
        Instant endedAt = started.startedAt().plusMillis(12);
        publisher.end(
            "ses_1",
            "toolu_1",
            ToolExecutionStatus.SUCCEEDED,
            0,
            summary,
            ref,
            started.startedAt(),
            endedAt,
            Map.of("toolName", "bash")
        );

        assertEquals(3, events.events.size());
        ToolStartEvent start = assertInstanceOf(ToolStartEvent.class, events.events.get(0));
        assertEquals("ses_1", start.sessionId());
        assertEquals("toolu_1", start.toolUseId());
        assertEquals("msg_1", start.parentMessageId());
        assertEquals("turn_1", start.turnId());
        assertEquals("bash", start.toolName());
        assertEquals("Bash", start.displayTitle());
        assertEquals("echo hello", start.inputSummary());
        assertEquals(start.startedAt(), start.timestamp());

        ToolProgressEvent progressEvent = assertInstanceOf(ToolProgressEvent.class, events.events.get(1));
        assertEquals("ses_1", progressEvent.sessionId());
        assertEquals("toolu_1", progressEvent.toolUseId());
        assertEquals(ToolProgressKind.STATUS, progressEvent.progress().kind());
        assertEquals("running command", progressEvent.progress().title());

        ToolEndEvent end = assertInstanceOf(ToolEndEvent.class, events.events.get(2));
        assertEquals("ses_1", end.sessionId());
        assertEquals("toolu_1", end.toolUseId());
        assertEquals(ToolExecutionStatus.SUCCEEDED, end.status());
        assertEquals(0, end.exitCode());
        assertEquals("bash succeeded", end.resultSummary().title());
        assertEquals("toolout_1", end.resultRef().refId());
        assertEquals(12L, end.durationMillis());
        assertEquals(end.endedAt(), end.timestamp());
    }

    @Test
    void ignoresNullProgress() {
        RecordingEventBus events = new RecordingEventBus();
        ToolExecutionEventPublisher publisher = ToolExecutionEventPublisher.eventBus(events);

        ToolExecutionEventPublisher.StartedToolExecution started = publisher.start(
            "ses_1",
            "toolu_1",
            "msg_1",
            null,
            "bash",
            "Bash",
            "",
            Map.of()
        );
        started.progressSink().progress(null);
        publisher.end(
            "ses_1",
            "toolu_1",
            ToolExecutionStatus.SUCCEEDED,
            null,
            new ToolResultSummary("bash succeeded", "", false, null, false, 0L, Map.of()),
            null,
            started.startedAt(),
            started.startedAt(),
            Map.of()
        );

        assertEquals(2, events.events.size());
        assertInstanceOf(ToolStartEvent.class, events.events.get(0));
        assertInstanceOf(ToolEndEvent.class, events.events.get(1));
    }

    @Test
    void publishFailureDoesNotThrow() {
        ToolExecutionEventPublisher publisher = ToolExecutionEventPublisher.eventBus(new ThrowingEventBus());

        assertDoesNotThrow(() -> {
            ToolExecutionEventPublisher.StartedToolExecution started = publisher.start(
                "ses_1",
                "toolu_1",
                "msg_1",
                "turn_1",
                "bash",
                "Bash",
                "",
                Map.of()
            );
            started.progressSink().progress(ToolProgress.status("running command", null));
            publisher.end(
                "ses_1",
                "toolu_1",
                ToolExecutionStatus.FAILED,
                null,
                new ToolResultSummary("bash failed", "boom", true, null, false, 4L, Map.of()),
                null,
                started.startedAt(),
                started.startedAt(),
                Map.of()
            );
        });
    }

    @Test
    void noopPublisherDoesNothing() {
        ToolExecutionEventPublisher publisher = ToolExecutionEventPublisher.noop();

        assertDoesNotThrow(() -> {
            ToolExecutionEventPublisher.StartedToolExecution started = publisher.start(
                "ses_1",
                "toolu_1",
                "msg_1",
                null,
                "bash",
                "Bash",
                "",
                Map.of()
            );
            started.progressSink().progress(ToolProgress.status("running command", null));
            publisher.end(
                "ses_1",
                "toolu_1",
                ToolExecutionStatus.SUCCEEDED,
                null,
                new ToolResultSummary("bash succeeded", "", false, null, false, 0L, Map.of()),
                null,
                started.startedAt(),
                started.startedAt(),
                Map.of()
            );
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
