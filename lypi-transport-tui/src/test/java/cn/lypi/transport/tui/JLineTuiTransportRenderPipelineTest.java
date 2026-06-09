package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import cn.lypi.contracts.event.RetryStartEvent;
import cn.lypi.contracts.event.ToolEndEvent;
import cn.lypi.contracts.event.ToolStartEvent;
import cn.lypi.contracts.tui.SessionRuntimeState;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

    @Test
    void eventRenderProjectsRuntimeStateIntoStatusBar() {
        RecordingEventBus events = new RecordingEventBus();
        List<List<String>> frames = new ArrayList<>();
        JLineTuiTransport transport = JLineTuiTransport.withRenderer(frames::add, 120, 4);

        transport.attach(events, TestRuntimeStates.basic("ses_1"));
        events.emit(new MessageDeltaEvent(
            "ses_1",
            "msg_1",
            MessageRole.ASSISTANT,
            MessageKind.TEXT,
            "block_1",
            ContentBlockKind.TEXT,
            "hello",
            true,
            java.util.Map.of(),
            Instant.parse("2026-06-09T00:00:00Z")
        ));

        assertEquals(
            "ses_1 gpt-5.4 EXECUTE DEFAULT_EXECUTE",
            frames.getLast().getLast()
        );
    }

    @Test
    void inputRerenderPreservesRuntimeStatusBar() throws Exception {
        RecordingEventBus events = new RecordingEventBus();
        List<List<String>> frames = new ArrayList<>();
        JLineTuiTransport transport = JLineTuiTransport.withInput(
            frames::add,
            120,
            4,
            new QueueInputSource("draft"),
            new RecordingSubmitHandler()
        );

        transport.attach(events, TestRuntimeStates.basic("ses_1"));
        events.emit(new MessageDeltaEvent(
            "ses_1",
            "msg_1",
            MessageRole.ASSISTANT,
            MessageKind.TEXT,
            "block_1",
            ContentBlockKind.TEXT,
            "hello",
            true,
            java.util.Map.of(),
            Instant.parse("2026-06-09T00:00:00Z")
        ));
        transport.drainInputForTest();

        assertEquals(
            "ses_1 gpt-5.4 EXECUTE DEFAULT_EXECUTE",
            frames.getLast().getLast()
        );
        assertEquals("\033[48;5;236m> draft|CURSOR|\033[0m", frames.getLast().get(frames.getLast().size() - 2));
    }

    @Test
    void resizeRerendersCurrentViewWithUpdatedDimensionsUnderUiLock() {
        RecordingEventBus events = new RecordingEventBus();
        List<List<String>> frames = new ArrayList<>();
        JLineTuiTransport transport = JLineTuiTransport.withRenderer(frames::add, 20, 5);

        transport.attach(events, TestRuntimeStates.basic("ses_1"));
        events.emit(new MessageDeltaEvent(
            "ses_1",
            "msg_1",
            MessageRole.ASSISTANT,
            MessageKind.TEXT,
            "block_1",
            ContentBlockKind.TEXT,
            "abcdefghijklmnopqrst",
            true,
            java.util.Map.of(),
            Instant.parse("2026-06-09T00:00:00Z")
        ));

        transport.resizeForTest(8, 4);

        assertEquals(2, frames.size());
        assertEquals(4, frames.getLast().size());
        frames.getLast().forEach(line -> assertEquals(line, AnsiWidth.truncate(line, 8)));
        assertEquals(2, transport.uiLockEntryCountForTest());
    }

    @Test
    void inputRerenderPreservesCurrentTranscriptView() throws Exception {
        RecordingEventBus events = new RecordingEventBus();
        List<List<String>> frames = new ArrayList<>();
        JLineTuiTransport transport = JLineTuiTransport.withInput(
            frames::add,
            40,
            5,
            new QueueInputSource("draft"),
            new RecordingSubmitHandler()
        );

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
        transport.drainInputForTest();

        assertEquals("Done", frames.getLast().getFirst());
        assertEquals("\033[48;5;236m> draft|CURSOR|\033[0m", frames.getLast().get(frames.getLast().size() - 2));
    }

    @Test
    void eventRerenderPreservesCurrentDraftInput() throws Exception {
        RecordingEventBus events = new RecordingEventBus();
        List<List<String>> frames = new ArrayList<>();
        JLineTuiTransport transport = JLineTuiTransport.withInput(
            frames::add,
            40,
            5,
            new QueueInputSource("draft"),
            new RecordingSubmitHandler()
        );

        transport.attach(events, TestRuntimeStates.basic("ses_1"));
        transport.drainInputForTest();
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

        assertEquals("Done", frames.getLast().getFirst());
        assertEquals("\033[48;5;236m> draft|CURSOR|\033[0m", frames.getLast().get(frames.getLast().size() - 2));
    }

    @Test
    void eventRerenderPreservesCurrentDraftCursor() throws Exception {
        RecordingEventBus events = new RecordingEventBus();
        List<List<String>> frames = new ArrayList<>();
        JLineTuiTransport transport = JLineTuiTransport.withInput(
            frames::add,
            40,
            5,
            new QueueInputSource("draft", "\033[D", "\033[D"),
            new RecordingSubmitHandler()
        );

        transport.attach(events, TestRuntimeStates.basic("ses_1"));
        transport.drainInputForTest();
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

        assertEquals("\033[48;5;236m> dra|CURSOR|ft\033[0m", frames.getLast().get(frames.getLast().size() - 2));
    }

    @Test
    void retryStatusRendersAsTransientTranscriptLineWithoutMovingStatusBar() {
        RecordingEventBus events = new RecordingEventBus();
        List<List<String>> frames = new ArrayList<>();
        JLineTuiTransport transport = JLineTuiTransport.withRenderer(frames::add, 80, 5);

        transport.attach(events, TestRuntimeStates.basic("ses_1"));
        events.emit(new MessageDeltaEvent(
            "ses_1",
            "msg_1",
            MessageRole.ASSISTANT,
            MessageKind.TEXT,
            "block_1",
            ContentBlockKind.TEXT,
            "hello",
            true,
            java.util.Map.of(),
            Instant.parse("2026-06-09T00:00:00Z")
        ));
        events.emit(new RetryStartEvent("ses_1", 2, "rate limit", Instant.parse("2026-06-09T00:00:01Z")));

        List<String> latest = frames.getLast();
        assertEquals("hello", latest.get(0));
        assertEquals("· retrying attempt 2 rate limit", latest.get(1));
        assertEquals("", latest.get(2));
        assertEquals("\033[48;5;236m> \033[0m", latest.get(3));
        assertTrue(latest.getLast().contains("ses_1"));
    }

    @Test
    void inputAfterResizeUsesUpdatedDimensions() throws Exception {
        RecordingEventBus events = new RecordingEventBus();
        List<List<String>> frames = new ArrayList<>();
        QueueInputSource input = new QueueInputSource("abcdefghijklmnop");
        JLineTuiTransport transport = JLineTuiTransport.withInput(
            frames::add,
            20,
            5,
            input,
            new RecordingSubmitHandler()
        );

        transport.attach(events, TestRuntimeStates.basic("ses_1"));
        transport.resizeForTest(8, 4);
        transport.drainInputForTest();

        assertEquals(4, frames.getLast().size());
        frames.getLast().forEach(line -> assertEquals(
            line.replace(TerminalFrameRenderer.CURSOR_MARKER, ""),
            AnsiWidth.truncate(line.replace(TerminalFrameRenderer.CURSOR_MARKER, ""), 8)
        ));
    }

    @Test
    void runUntilExitReturnsWhenCtrlCRequestsExit() throws Exception {
        JLineTuiTransport transport = JLineTuiTransport.withInput(
            ignored -> {
            },
            40,
            5,
            new QueueInputSource("\u0003"),
            new RecordingSubmitHandler()
        );

        transport.runUntilExit();

        assertEquals(true, transport.exitRequestedForTest());
    }

    @Test
    void transportDrainProcessesBoundedInputBatch() throws Exception {
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            chunks.add("a");
        }
        JLineTuiTransport transport = JLineTuiTransport.withInput(
            ignored -> {
            },
            80,
            5,
            new QueueInputSource(chunks.toArray(String[]::new)),
            new RecordingSubmitHandler()
        );

        transport.drainInputForTest();

        assertEquals(32, transport.currentDraftLengthForTest());
    }

    @Test
    void ctrlCInterruptsRunningToolAfterToolStartEvent() throws Exception {
        RecordingEventBus events = new RecordingEventBus();
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        JLineTuiTransport transport = JLineTuiTransport.withInput(
            ignored -> {
            },
            40,
            5,
            new QueueInputSource("\u0003"),
            submit
        );

        transport.attach(events, TestRuntimeStates.basic("ses_1"));
        events.emit(new ToolStartEvent("ses_1", "tool_1", "bash", Instant.parse("2026-06-09T00:00:00Z")));
        transport.drainInputForTest();

        assertEquals(1, submit.interrupts);
        assertEquals(false, transport.exitRequestedForTest());
    }

    @Test
    void ctrlCInterruptsRuntimeInterruptibleToolWithoutToolStartReplay() throws Exception {
        RecordingEventBus events = new RecordingEventBus();
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        JLineTuiTransport transport = JLineTuiTransport.withInput(
            ignored -> {
            },
            40,
            5,
            new QueueInputSource("\u0003"),
            submit
        );

        transport.attach(events, TestRuntimeStates.interruptible("ses_1"));
        transport.drainInputForTest();

        assertEquals(1, submit.interrupts);
        assertEquals(false, transport.exitRequestedForTest());
    }

    @Test
    void ctrlCRequestsExitAfterToolEndEvent() throws Exception {
        RecordingEventBus events = new RecordingEventBus();
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        JLineTuiTransport transport = JLineTuiTransport.withInput(
            ignored -> {
            },
            40,
            5,
            new QueueInputSource("\u0003"),
            submit
        );

        transport.attach(events, TestRuntimeStates.basic("ses_1"));
        events.emit(new ToolStartEvent("ses_1", "tool_1", "bash", Instant.parse("2026-06-09T00:00:00Z")));
        events.emit(new ToolEndEvent("ses_1", "tool_1", false, Instant.parse("2026-06-09T00:00:01Z")));
        transport.drainInputForTest();

        assertEquals(0, submit.interrupts);
        assertEquals(true, transport.exitRequestedForTest());
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

    private static final class QueueInputSource implements TerminalInputSource {
        private final ArrayDeque<String> chunks;

        private QueueInputSource(String... chunks) {
            this.chunks = new ArrayDeque<>(List.of(chunks));
        }

        @Override
        public Optional<String> read() {
            return Optional.ofNullable(chunks.pollFirst());
        }
    }

    private static final class RecordingSubmitHandler implements TuiSubmitHandler {
        private int interrupts;

        @Override
        public void submitUserInput(String input) {
        }

        @Override
        public void requestInterrupt(String reason) {
            interrupts++;
        }
    }
}
