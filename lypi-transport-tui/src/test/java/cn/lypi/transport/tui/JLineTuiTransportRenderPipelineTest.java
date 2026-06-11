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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class JLineTuiTransportRenderPipelineTest {
    private static final String INPUT_BACKGROUND = "\033[48;5;236m";
    private static final String INPUT_CURSOR = "\033[38;5;81m|\033[39m";
    private static final String ANSI_RESET = "\033[0m";

    @Test
    void eventCallbackReducesAndRendersViewModelUnderUiLock() {
        RecordingEventBus events = new RecordingEventBus();
        List<String> frames = new ArrayList<>();
        JLineTuiTransport transport = JLineTuiTransport.withRenderer(lines -> frames.add(String.join("\n", lines)), 40, 5);

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
    void rendererFrameKeepsInputBlockAfterFullTranscriptForTerminalScrollback() {
        RecordingEventBus events = new RecordingEventBus();
        List<List<String>> frames = new ArrayList<>();
        JLineTuiTransport transport = JLineTuiTransport.withRenderer(frames::add, 40, 7);

        transport.attach(events, TestRuntimeStates.basic("ses_1"));
        for (int i = 0; i < 6; i++) {
            events.emit(new MessageDeltaEvent(
                "ses_1",
                "msg_" + i,
                MessageRole.ASSISTANT,
                MessageKind.TEXT,
                "block_" + i,
                ContentBlockKind.TEXT,
                "line " + i,
                true,
                java.util.Map.of(),
                Instant.parse("2026-06-09T00:00:00Z")
            ));
        }

        List<String> latest = frames.getLast();
        assertEquals(10, latest.size());
        assertTrue(latest.contains("line 0"));
        assertTrue(latest.contains("line 3"));
        assertTrue(latest.contains("line 5"));
        assertEquals(inputContent("> "), inputLine(latest));
        assertTrue(latest.getLast().contains("ses_1"));
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
    void eventPipelineRendersUserAndThinkingAsDistinctLines() {
        RecordingEventBus events = new RecordingEventBus();
        List<List<String>> frames = new ArrayList<>();
        JLineTuiTransport transport = JLineTuiTransport.withRenderer(frames::add, 80, 5);

        transport.attach(events, TestRuntimeStates.basic("ses_1"));
        events.emit(new MessageDeltaEvent(
            "ses_1",
            "msg_user",
            MessageRole.USER,
            MessageKind.TEXT,
            "block_user",
            ContentBlockKind.TEXT,
            "请修复 TUI",
            true,
            java.util.Map.of(),
            Instant.parse("2026-06-09T00:00:00Z")
        ));
        events.emit(new MessageDeltaEvent(
            "ses_1",
            "msg_assistant",
            MessageRole.ASSISTANT,
            MessageKind.THINKING,
            "block_thinking",
            ContentBlockKind.THINKING,
            "分析路径",
            true,
            java.util.Map.of(),
            Instant.parse("2026-06-09T00:00:01Z")
        ));
        events.emit(new MessageDeltaEvent(
            "ses_1",
            "msg_assistant",
            MessageRole.ASSISTANT,
            MessageKind.TEXT,
            "block_answer",
            ContentBlockKind.TEXT,
            "已处理",
            true,
            java.util.Map.of(),
            Instant.parse("2026-06-09T00:00:02Z")
        ));

        List<String> latest = frames.getLast();
        assertTrue(latest.contains("\033[38;5;81muser: 请修复 TUI\033[0m"));
        assertTrue(latest.contains("\033[38;5;244mthinking: 分析路径\033[0m"));
        assertTrue(latest.contains("已处理"));
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
        assertEquals(inputContent("> draft|CURSOR|" + INPUT_CURSOR), inputLine(frames.getLast()));
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
        assertEquals(inputContent("> draft|CURSOR|" + INPUT_CURSOR), inputLine(frames.getLast()));
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
        assertEquals(inputContent("> draft|CURSOR|" + INPUT_CURSOR), inputLine(frames.getLast()));
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

        assertEquals(inputContent("> dra|CURSOR|" + INPUT_CURSOR + "ft"), inputLine(frames.getLast()));
    }

    @Test
    void retryStatusRendersAsTransientTranscriptLineWithoutMovingStatusBar() {
        RecordingEventBus events = new RecordingEventBus();
        List<List<String>> frames = new ArrayList<>();
        JLineTuiTransport transport = JLineTuiTransport.withRenderer(frames::add, 80, 6);

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
        assertEquals(inputContent("> "), inputLine(latest));
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
    void runUntilExitReturnsAfterInterruptSignalRequestsExitWhileInputReadIsWaiting() throws Exception {
        RecordingEventBus events = new RecordingEventBus();
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        RecordingTerminalIo io = new RecordingTerminalIo();
        CountDownLatch inputReadStarted = new CountDownLatch(1);
        CountDownLatch releaseInputRead = new CountDownLatch(1);
        WaitingInputSource input = new WaitingInputSource(inputReadStarted, releaseInputRead);
        JLineTuiTransport transport = JLineTuiTransport.open(
            TestRuntimeStates.basic("ses_1"),
            events,
            io,
            input,
            submit,
            40,
            5
        );
        Thread runner = new Thread(() -> {
            try {
                transport.runUntilExit();
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        });

        runner.start();
        assertTrue(inputReadStarted.await(1, TimeUnit.SECONDS));
        io.triggerInterrupt();
        releaseInputRead.countDown();
        runner.join(1_000L);

        assertEquals(false, runner.isAlive());
        assertEquals(1, submit.exits);
        assertEquals(true, transport.exitRequestedForTest());
        transport.close();
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

    @Test
    void interruptSignalClearsNonEmptyDraftUnderUiLock() throws Exception {
        RecordingEventBus events = new RecordingEventBus();
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        RecordingTerminalIo io = new RecordingTerminalIo();
        JLineTuiTransport transport = JLineTuiTransport.open(
            TestRuntimeStates.basic("ses_1"),
            events,
            io,
            new QueueInputSource("draft"),
            submit,
            40,
            5
        );
        transport.drainInputForTest();
        int lockEntries = transport.uiLockEntryCountForTest();

        io.triggerInterrupt();

        assertEquals(0, transport.currentDraftLengthForTest());
        assertEquals(0, submit.exits);
        assertEquals(0, submit.interrupts);
        assertTrue(transport.uiLockEntryCountForTest() > lockEntries);
        transport.close();
    }

    @Test
    void interruptSignalRequestsExitWhenDraftIsEmptyAndNoToolIsRunning() throws Exception {
        RecordingEventBus events = new RecordingEventBus();
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        RecordingTerminalIo io = new RecordingTerminalIo();
        JLineTuiTransport transport = JLineTuiTransport.open(
            TestRuntimeStates.basic("ses_1"),
            events,
            io,
            new QueueInputSource(),
            submit,
            40,
            5
        );

        io.triggerInterrupt();

        assertEquals(1, submit.exits);
        assertEquals(0, submit.interrupts);
        assertEquals(true, transport.exitRequestedForTest());
        transport.close();
    }

    @Test
    void interruptSignalInterruptsRunningToolWhenDraftIsEmpty() throws Exception {
        RecordingEventBus events = new RecordingEventBus();
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        RecordingTerminalIo io = new RecordingTerminalIo();
        JLineTuiTransport transport = JLineTuiTransport.open(
            TestRuntimeStates.basic("ses_1"),
            events,
            io,
            new QueueInputSource(),
            submit,
            40,
            5
        );
        events.emit(new ToolStartEvent("ses_1", "tool_1", "bash", Instant.parse("2026-06-09T00:00:00Z")));

        io.triggerInterrupt();

        assertEquals(0, submit.exits);
        assertEquals(1, submit.interrupts);
        assertEquals(false, transport.exitRequestedForTest());
        transport.close();
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

    private static final class WaitingInputSource implements TerminalInputSource {
        private final CountDownLatch waitStarted;
        private final CountDownLatch release;

        private WaitingInputSource(CountDownLatch waitStarted, CountDownLatch release) {
            this.waitStarted = waitStarted;
            this.release = release;
        }

        @Override
        public Optional<String> read() throws java.io.IOException {
            waitStarted.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("interrupted while waiting", exception);
            }
            return Optional.empty();
        }
    }

    private static String inputLine(List<String> frame) {
        return frame.stream()
            .filter(line -> line.startsWith(INPUT_BACKGROUND))
            .reduce((first, second) -> second)
            .orElseThrow();
    }

    private static String inputContent(String content) {
        return INPUT_BACKGROUND + content + ANSI_RESET;
    }

    private static final class RecordingSubmitHandler implements TuiSubmitHandler {
        private int interrupts;
        private int exits;

        @Override
        public void submitUserInput(String input) {
        }

        @Override
        public void requestInterrupt(String reason) {
            interrupts++;
        }

        @Override
        public void requestExit(String reason) {
            exits++;
        }
    }

    private static final class RecordingTerminalIo implements TerminalIo {
        private final StringBuilder output = new StringBuilder();
        private Runnable interruptCallback = () -> {
        };

        @Override
        public AutoCloseable enterRawMode() {
            return () -> {
            };
        }

        @Override
        public void write(String value) {
            output.append(value);
        }

        @Override
        public void flush() {
        }

        @Override
        public int width() {
            return 40;
        }

        @Override
        public int height() {
            return 5;
        }

        @Override
        public AutoCloseable onResize(Runnable callback) {
            return () -> {
            };
        }

        @Override
        public AutoCloseable onInterrupt(Runnable callback) {
            interruptCallback = callback;
            return () -> {
            };
        }

        void triggerInterrupt() {
            interruptCallback.run();
        }
    }
}
