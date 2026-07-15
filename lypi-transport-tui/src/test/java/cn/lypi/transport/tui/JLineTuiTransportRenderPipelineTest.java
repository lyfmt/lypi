package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlockKind;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.EventConsumer;
import cn.lypi.contracts.event.EventEnvelope;
import cn.lypi.contracts.event.EventFilter;
import cn.lypi.contracts.event.EventSubscription;
import cn.lypi.contracts.event.MessageDeltaEvent;
import cn.lypi.contracts.event.PermissionRequestEvent;
import cn.lypi.contracts.event.ProviderFallbackEndEvent;
import cn.lypi.contracts.event.ProviderFallbackStartEvent;
import cn.lypi.contracts.event.RetryStartEvent;
import cn.lypi.contracts.event.ToolEndEvent;
import cn.lypi.contracts.event.ToolProgressEvent;
import cn.lypi.contracts.event.ToolStartEvent;
import cn.lypi.contracts.event.TurnStartEvent;
import cn.lypi.contracts.tui.SessionRuntimeState;
import cn.lypi.contracts.tui.TuiToolBlock;
import cn.lypi.contracts.tui.TuiToolState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class JLineTuiTransportRenderPipelineTest {
    private static final String INPUT_BACKGROUND = "\033[48;5;236m";
    private static final String INPUT_CURSOR = "\033[38;5;81m|\033[39m";
    private static final String ANSI_RESET = "\033[0m";

    @Test
    void completedRuntimeTranscriptRendersFinalToolStatesOnFirstFrame() {
        RecordingEventBus events = new RecordingEventBus();
        List<List<String>> frames = new ArrayList<>();
        JLineTuiTransport transport = JLineTuiTransport.withRenderer(frames::add, 100, 12);

        transport.attach(events, completedRuntimeState("ses_resumed", 0));
        transport.renderCurrentFrameUnderUiLock();

        TuiToolBlock read = tool(transport, "read-1");
        TuiToolBlock bash = tool(transport, "bash-1");
        assertEquals(TuiToolState.DONE, read.state());
        assertFalse(read.active());
        assertTrue(read.details().contains("read result summary"));
        assertEquals(TuiToolState.FAILED, bash.state());
        assertFalse(bash.active());
        assertTrue(bash.details().contains("command failed"));

        String firstFrame = String.join("\n", frames.getFirst());
        assertTrue(firstFrame.contains("tools: read x1 (Ctrl+O details)"), firstFrame);
        assertTrue(firstFrame.contains("failed $ exit 1"), firstFrame);
        assertTrue(firstFrame.contains("command failed"), firstFrame);
        assertTrue(firstFrame.contains("resume complete"), firstFrame);
        assertFalse(firstFrame.contains("pending read"), firstFrame);
        assertFalse(firstFrame.contains("pending $"), firstFrame);
    }

    @Test
    void reattachToResumedStateClearsOldProgressAndScrollsOnlyNewTranscript() throws Exception {
        RecordingEventBus events = new RecordingEventBus();
        List<List<String>> frames = new ArrayList<>();
        QueueInputSource input = new QueueInputSource();
        JLineTuiTransport transport = JLineTuiTransport.withInput(
            frames::add,
            80,
            8,
            input,
            new RecordingSubmitHandler()
        );
        transport.attach(events, TestRuntimeStates.basic("ses_old"));
        events.emit(new ToolStartEvent("ses_old", "old-tool", "bash", Instant.parse("2026-06-09T00:00:00Z")));
        events.emit(new ToolProgressEvent(
            "ses_old",
            "old-tool",
            ToolProgress.output("stdout", "old-progress\n"),
            Instant.parse("2026-06-09T00:00:01Z")
        ));
        assertEquals(TuiToolState.RUNNING, tool(transport, "old-tool").state());
        assertTrue(tool(transport, "old-tool").details().contains("old-progress"));

        SessionRuntimeState resumed = completedRuntimeState("ses_new", 12);
        transport.attach(events, resumed);
        transport.renderCurrentFrameUnderUiLock();

        assertFalse(transport.viewForTest().blocks().stream()
            .filter(TuiToolBlock.class::isInstance)
            .map(TuiToolBlock.class::cast)
            .anyMatch(block -> "old-tool".equals(block.toolUseId())));
        assertFalse(transport.viewForTest().blocks().stream()
            .filter(TuiToolBlock.class::isInstance)
            .map(TuiToolBlock.class::cast)
            .anyMatch(block -> block.details().contains("old-progress")));
        assertEquals(TuiToolState.DONE, tool(transport, "read-1").state());
        assertEquals(TuiToolState.FAILED, tool(transport, "bash-1").state());

        events.emit(new ToolProgressEvent(
            "ses_new",
            "bash-1",
            ToolProgress.output("stderr", "new-index-progress\n"),
            Instant.parse("2026-06-09T00:00:02Z")
        ));
        assertEquals(TuiToolState.DONE, tool(transport, "read-1").state());
        assertEquals(TuiToolState.RUNNING, tool(transport, "bash-1").state());
        assertTrue(tool(transport, "bash-1").details().contains("new-index-progress"));

        transport.attach(events, resumed);
        transport.renderCurrentFrameUnderUiLock();
        for (int index = 0; index < 10; index++) {
            input.add("\033[6~");
        }
        transport.drainInputForTest();
        assertTrue(String.join("\n", frames.getLast()).contains("resume complete"));

        for (int index = 0; index < 10; index++) {
            input.add("\033[5~");
        }
        transport.drainInputForTest();
        String pageUpFrame = String.join("\n", frames.getLast());
        assertTrue(pageUpFrame.contains("new history 0"), pageUpFrame);
        assertFalse(pageUpFrame.contains("old-progress"), pageUpFrame);

        for (int index = 0; index < 10; index++) {
            input.add("\033[6~");
        }
        transport.drainInputForTest();
        String pageDownFrame = String.join("\n", frames.getLast());
        assertTrue(pageDownFrame.contains("resume complete"), pageDownFrame);
        assertFalse(pageDownFrame.contains("old-progress"), pageDownFrame);
    }

    @Test
    void changingSessionClearsHistoryScrollOffsetBeforeFirstFrame() throws Exception {
        RecordingEventBus events = new RecordingEventBus();
        List<List<String>> frames = new ArrayList<>();
        QueueInputSource input = new QueueInputSource();
        JLineTuiTransport transport = JLineTuiTransport.withInput(
            frames::add,
            80,
            8,
            input,
            new RecordingSubmitHandler()
        );
        transport.attach(events, completedRuntimeState("ses_old", 40));
        transport.renderCurrentFrameUnderUiLock();
        for (int index = 0; index < 20; index++) {
            input.add("\033[5~");
        }
        transport.drainInputForTest();
        assertTrue(frames.getLast().getLast().contains("↑"));

        transport.attach(events, completedRuntimeState("ses_new", 5));
        transport.renderCurrentFrameUnderUiLock();

        String firstNewFrame = String.join("\n", frames.getLast());
        assertTrue(firstNewFrame.contains("resume complete"), firstNewFrame);
        assertFalse(firstNewFrame.contains("new history"), firstNewFrame);
        assertFalse(frames.getLast().getLast().contains("↑"));
    }

    @Test
    void pageUpChangesRestoredHistoryWithoutMovingLiveToolOrBottomChrome() throws Exception {
        RecordingEventBus events = new RecordingEventBus();
        List<List<String>> frames = new ArrayList<>();
        QueueInputSource input = new QueueInputSource();
        JLineTuiTransport transport = JLineTuiTransport.withInput(
            frames::add,
            80,
            10,
            input,
            new RecordingSubmitHandler()
        );
        transport.attach(events, historyRuntimeState("ses_1", 30));
        events.emit(new ToolStartEvent(
            "ses_1",
            "live-tool",
            "bash",
            Instant.parse("2026-06-09T00:00:00Z")
        ));
        transport.flushPendingFrameForTest();
        List<String> tailFrame = frames.getLast();

        input.add("\033[5~");
        transport.drainInputForTest();
        List<String> pageUpFrame = frames.getLast();

        assertTrue(tailFrame.contains("history-line-30"));
        assertFalse(pageUpFrame.contains("history-line-30"));
        assertTrue(pageUpFrame.stream().anyMatch(line -> line.startsWith("running $")));
        assertEquals(
            tailFrame.stream().filter(line -> line.startsWith("running $")).findFirst(),
            pageUpFrame.stream().filter(line -> line.startsWith("running $")).findFirst()
        );
        assertEquals(
            tailFrame.stream().filter(line -> line.contains(INPUT_BACKGROUND)).findFirst(),
            pageUpFrame.stream().filter(line -> line.contains(INPUT_BACKGROUND)).findFirst()
        );
        assertEquals(10, tailFrame.size());
        assertEquals(10, pageUpFrame.size());
        assertTrue(tailFrame.stream().anyMatch(line -> line.contains("┄")));
        assertTrue(pageUpFrame.stream().anyMatch(line -> line.contains("┄")));
        assertTrue(tailFrame.getLast().contains("ses_1"));
        assertTrue(pageUpFrame.getLast().contains("ses_1"));
    }

    @Test
    void restoredHistoryCanScrollBackOnlyThroughLatestFiveHundredPhysicalLines() throws Exception {
        RecordingEventBus events = new RecordingEventBus();
        List<List<String>> frames = new ArrayList<>();
        QueueInputSource input = new QueueInputSource();
        JLineTuiTransport transport = JLineTuiTransport.withInput(
            frames::add,
            80,
            8,
            input,
            new RecordingSubmitHandler()
        );
        transport.attach(events, historyRuntimeState("ses_1", 510));
        transport.renderCurrentFrameUnderUiLock();
        for (int index = 0; index < 200; index++) {
            input.add("\033[5~");
        }
        for (int batch = 0; batch < 7; batch++) {
            transport.drainInputForTest();
        }

        List<String> topFrame = frames.getLast();
        assertTrue(topFrame.contains("history-line-11"), String.join("\n", topFrame));
        for (int index = 1; index <= 10; index++) {
            assertFalse(topFrame.contains("history-line-" + index));
        }
    }

    @Test
    void toolProgressBurstReducesImmediatelyAndCoalescesTerminalFrames() {
        RecordingEventBus events = new RecordingEventBus();
        AtomicLong now = new AtomicLong();
        List<List<String>> frames = new ArrayList<>();
        JLineTuiTransport transport = JLineTuiTransport.withRenderer(
            frames::add,
            80,
            8,
            now::get,
            TuiRedrawScheduler.DEFAULT_FRAME_INTERVAL_NANOS
        );
        transport.attach(events, TestRuntimeStates.basic("ses_1"));

        events.emit(new ToolStartEvent("ses_1", "toolu_1", "bash", Instant.parse("2026-06-09T00:00:00Z")));
        for (int index = 0; index < 256; index++) {
            events.emit(new ToolProgressEvent(
                "ses_1",
                "toolu_1",
                ToolProgress.output("stdout", "chunk-" + index + "\n"),
                Instant.parse("2026-06-09T00:00:00Z")
            ));
        }
        events.emit(new ToolEndEvent("ses_1", "toolu_1", false, Instant.parse("2026-06-09T00:00:01Z")));

        TuiToolBlock tool = (TuiToolBlock) transport.viewForTest().blocks().getFirst();
        assertEquals(TuiToolState.DONE, tool.state());
        assertFalse(tool.active());
        assertTrue(frames.isEmpty());

        now.addAndGet(TuiRedrawScheduler.DEFAULT_FRAME_INTERVAL_NANOS);
        assertTrue(transport.renderPendingFrameIfDueForTest());

        assertEquals(1, frames.size());
        String finalFrame = String.join("\n", frames.getFirst());
        assertTrue(finalFrame.contains("status succeeded"), finalFrame);
    }

    @Test
    void providerBurstRendersLeadingTextDeltaBeforeFinalFrame() {
        RecordingEventBus events = new RecordingEventBus();
        AtomicLong now = new AtomicLong();
        List<List<String>> frames = new ArrayList<>();
        JLineTuiTransport transport = JLineTuiTransport.withRenderer(
            frames::add,
            80,
            8,
            now::get,
            TuiRedrawScheduler.DEFAULT_FRAME_INTERVAL_NANOS
        );
        transport.attach(events, TestRuntimeStates.basic("ses_1"));

        events.emit(textDelta("first", false));
        assertTrue(String.join("\n", frames.getLast()).contains("first"));
        assertFalse(String.join("\n", frames.getLast()).contains("first-final"));

        events.emit(textDelta("-final", true));
        assertEquals(1, frames.size());

        now.addAndGet(TuiRedrawScheduler.DEFAULT_FRAME_INTERVAL_NANOS);
        assertTrue(transport.renderPendingFrameIfDueForTest());
        assertTrue(String.join("\n", frames.getLast()).contains("first-final"));
    }

    @Test
    void visibleDeltaReducesAndRendersUnderOneUiLockEntry() {
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
    void eventRenderingUsesTranscriptViewportInsteadOfReturningAnOversizedFrame() {
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
        transport.flushPendingFrameForTest();

        List<String> latest = frames.getLast();
        assertTrue(latest.size() <= 7);
        assertFalse(latest.contains("line 0"));
        assertFalse(latest.contains("line 2"));
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
        transport.flushPendingFrameForTest();

        assertEquals(
            "ses_1 gpt-5.4 EXECUTE DEFAULT_EXECUTE ON_REQUEST :workspace",
            frames.getLast().getLast()
        );
    }

    @Test
    void eventPipelineRendersUserAndThinkingAsDistinctLines() {
        RecordingEventBus events = new RecordingEventBus();
        List<List<String>> frames = new ArrayList<>();
        JLineTuiTransport transport = JLineTuiTransport.withRenderer(frames::add, 80, 7);

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
        transport.flushPendingFrameForTest();

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
            "ses_1 gpt-5.4 EXECUTE DEFAULT_EXECUTE ON_REQUEST :workspace",
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
        transport.flushPendingFrameForTest();

        transport.resizeForTest(8, 4);

        assertEquals(2, frames.size());
        frames.getLast().forEach(line -> assertEquals(line, AnsiWidth.truncate(line, 8)));
        assertEquals(2, transport.uiLockEntryCountForTest());
    }

    @Test
    void resizePreservesScrolledTranscriptUntilPageDownReturnsToTail() throws Exception {
        RecordingEventBus events = new RecordingEventBus();
        List<List<String>> frames = new ArrayList<>();
        QueueInputSource input = new QueueInputSource();
        JLineTuiTransport transport = JLineTuiTransport.withInput(
            frames::add,
            40,
            6,
            input,
            new RecordingSubmitHandler()
        );

        transport.attach(events, TestRuntimeStates.basic("ses_1"));
        for (int index = 1; index <= 10; index++) {
            events.emit(new MessageDeltaEvent(
                "ses_1",
                "msg_" + index,
                MessageRole.ASSISTANT,
                MessageKind.TEXT,
                "block_" + index,
                ContentBlockKind.TEXT,
                "line " + index,
                true,
                java.util.Map.of(),
                Instant.parse("2026-06-09T00:00:00Z")
            ));
        }

        input.add("\033[5~");
        transport.drainInputForTest();
        long rowsBeforeResize = transcriptRows(frames.getLast());
        assertTrue(frames.getLast().contains("line 9"));
        assertFalse(frames.getLast().contains("line 10"));

        transport.resizeForTest(40, 8);

        assertTrue(transcriptRows(frames.getLast()) > rowsBeforeResize);
        assertTrue(frames.getLast().contains("line 9"));
        assertFalse(frames.getLast().contains("line 10"));

        input.add("\033[6~");
        transport.drainInputForTest();

        assertTrue(frames.getLast().contains("line 10"));
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
        transport.flushPendingFrameForTest();

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
        transport.flushPendingFrameForTest();

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
        transport.flushPendingFrameForTest();

        List<String> latest = frames.getLast();
        assertEquals("hello", latest.get(0));
        assertEquals("· retrying attempt 2 rate limit", latest.get(1));
        assertEquals(inputContent("> "), inputLine(latest));
        assertTrue(latest.getLast().contains("ses_1"));
    }

    @Test
    void providerFallbackRendersUntilSuccessfulOutputWithoutMovingStatusBar() {
        RecordingEventBus events = new RecordingEventBus();
        List<List<String>> frames = new ArrayList<>();
        JLineTuiTransport transport = JLineTuiTransport.withRenderer(frames::add, 100, 6);
        Instant timestamp = Instant.parse("2026-06-09T00:00:00Z");

        transport.attach(events, TestRuntimeStates.basic("ses_1"));
        events.emit(new ProviderFallbackStartEvent(
            "ses_1",
            "responses/websocket",
            "responses/sse",
            "provider.fallback_candidate",
            timestamp
        ));
        transport.flushPendingFrameForTest();

        List<String> fallbackFrame = frames.getLast();
        assertTrue(fallbackFrame.contains(
            "· fallback responses/websocket -> responses/sse provider.fallback_candidate"
        ));
        assertTrue(fallbackFrame.getLast().contains("ses_1"));

        events.emit(new ProviderFallbackEndEvent("ses_1", "responses/sse", true, timestamp.plusMillis(1)));
        events.emit(new MessageDeltaEvent(
            "ses_1",
            "msg_1",
            MessageRole.ASSISTANT,
            MessageKind.TEXT,
            "block_1",
            ContentBlockKind.TEXT,
            "fallback ok",
            true,
            java.util.Map.of(),
            timestamp.plusMillis(2)
        ));
        transport.flushPendingFrameForTest();

        List<String> outputFrame = frames.getLast();
        assertTrue(outputFrame.contains("fallback ok"));
        assertFalse(outputFrame.stream().anyMatch(line -> line.contains("· fallback")));
        assertEquals(fallbackFrame.size(), outputFrame.size());
        assertTrue(outputFrame.getLast().contains("ses_1"));
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
            line.replace(TuiRenderFrame.CURSOR_MARKER, ""),
            AnsiWidth.truncate(line.replace(TuiRenderFrame.CURSOR_MARKER, ""), 8)
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
    void runLoopRendersIntermediateMessageDeltaBeforeFinalDelta() throws Exception {
        RecordingEventBus events = new RecordingEventBus();
        List<List<String>> frames = new CopyOnWriteArrayList<>();
        QueueInputSource input = new QueueInputSource();
        JLineTuiTransport transport = JLineTuiTransport.withInput(
            frame -> frames.add(List.copyOf(frame)),
            80,
            8,
            input,
            new RecordingSubmitHandler()
        );
        transport.attach(events, TestRuntimeStates.basic("ses_1"));
        AtomicReference<Throwable> loopFailure = new AtomicReference<>();
        Thread runner = Thread.ofVirtual().start(() -> {
            try {
                transport.runUntilExit();
            } catch (Throwable failure) {
                loopFailure.set(failure);
            }
        });

        try {
            events.emit(new MessageDeltaEvent(
                "ses_1",
                "msg_stream",
                MessageRole.ASSISTANT,
                MessageKind.TEXT,
                "block_stream",
                ContentBlockKind.TEXT,
                "stream-first",
                false,
                Map.of(),
                Instant.parse("2026-06-09T00:00:00Z")
            ));

            assertTrue(awaitFrame(frames, frame -> String.join("\n", frame).contains("stream-first")));

            events.emit(new MessageDeltaEvent(
                "ses_1",
                "msg_stream",
                MessageRole.ASSISTANT,
                MessageKind.TEXT,
                "block_stream",
                ContentBlockKind.TEXT,
                "-final",
                true,
                Map.of(),
                Instant.parse("2026-06-09T00:00:01Z")
            ));

            assertTrue(awaitFrame(frames, frame -> String.join("\n", frame).contains("stream-first-final")));
        } finally {
            input.add("\u0003");
            runner.join(1_000L);
        }

        assertFalse(runner.isAlive());
        assertEquals(null, loopFailure.get());
    }

    @Test
    void runLoopPageUpScrollsHistoryBuiltFromCurrentSessionEvents() throws Exception {
        RecordingEventBus events = new RecordingEventBus();
        List<List<String>> frames = new CopyOnWriteArrayList<>();
        QueueInputSource input = new QueueInputSource();
        JLineTuiTransport transport = JLineTuiTransport.withInput(
            frame -> frames.add(List.copyOf(frame)),
            80,
            8,
            input,
            new RecordingSubmitHandler()
        );
        transport.attach(events, TestRuntimeStates.basic("ses_1"));
        for (int index = 1; index <= 30; index++) {
            events.emit(new MessageDeltaEvent(
                "ses_1",
                "msg_" + index,
                MessageRole.ASSISTANT,
                MessageKind.TEXT,
                "block_" + index,
                ContentBlockKind.TEXT,
                "event-history-" + index,
                true,
                Map.of(),
                Instant.parse("2026-06-09T00:00:00Z")
            ));
        }
        transport.renderCurrentFrameUnderUiLock();
        assertTrue(String.join("\n", frames.getLast()).contains("event-history-30"));

        AtomicReference<Throwable> loopFailure = new AtomicReference<>();
        Thread runner = Thread.ofVirtual().start(() -> {
            try {
                transport.runUntilExit();
            } catch (Throwable failure) {
                loopFailure.set(failure);
            }
        });

        try {
            input.add("\033[5~");
            assertTrue(awaitFrame(frames, frame -> !String.join("\n", frame).contains("event-history-30")));
        } finally {
            input.add("\u0003");
            runner.join(1_000L);
        }

        assertFalse(runner.isAlive());
        assertEquals(null, loopFailure.get());
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
    void escapeInterruptsRunningTurnBeforeToolStartEvent() throws Exception {
        RecordingEventBus events = new RecordingEventBus();
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        JLineTuiTransport transport = JLineTuiTransport.withInput(
            ignored -> {
            },
            40,
            5,
            new QueueInputSource("\033"),
            submit
        );

        transport.attach(events, TestRuntimeStates.basic("ses_1"));
        events.emit(new TurnStartEvent("ses_1", "turn_1", Instant.parse("2026-06-09T00:00:00Z")));
        transport.drainInputForTest();

        assertEquals(1, submit.interrupts);
        assertEquals(List.of("esc"), submit.interruptReasons);
        assertEquals(false, transport.exitRequestedForTest());
    }

    @Test
    void idleTickRefreshesWorkingTurnElapsedTime() throws Exception {
        RecordingEventBus events = new RecordingEventBus();
        MutableClock clock = new MutableClock(Instant.parse("2026-06-09T00:00:00Z"));
        List<List<String>> frames = new ArrayList<>();
        JLineTuiTransport transport = JLineTuiTransport.withInput(
            frames::add,
            80,
            5,
            new QueueInputSource(),
            new RecordingSubmitHandler(),
            clock
        );

        transport.attach(events, TestRuntimeStates.basic("ses_1"));
        events.emit(new TurnStartEvent("ses_1", "turn_1", clock.instant()));
        transport.flushPendingFrameForTest();

        assertTrue(frames.getLast().contains("· working (0s)"));

        clock.advanceSeconds(2);
        transport.renderRuntimeTickForTest();

        assertTrue(frames.getLast().contains("· working (2s)"));
    }

    @Test
    void permissionRequestEventRendersPromptImmediatelyWithoutInputKey() throws Exception {
        RecordingEventBus events = new RecordingEventBus();
        List<List<String>> frames = new ArrayList<>();
        JLineTuiTransport transport = JLineTuiTransport.withInput(
            frames::add,
            40,
            6,
            new QueueInputSource(),
            new RecordingSubmitHandler()
        );

        transport.attach(events, TestRuntimeStates.basic("ses_1"));
        events.emit(new TurnStartEvent("ses_1", "turn_1", Instant.parse("2026-06-09T00:00:00Z")));
        events.emit(new PermissionRequestEvent(
            "ses_1",
            "toolu_1",
            "Need approval",
            Instant.parse("2026-06-09T00:00:01Z")
        ));
        transport.flushPendingFrameForTest();

        List<String> latest = frames.getLast();
        assertTrue(latest.stream().anyMatch(line -> line.contains("permission toolu_1: Need approval")));
        assertTrue(latest.stream().anyMatch(line -> line.contains("> 允许一次")));
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

    private static SessionRuntimeState completedRuntimeState(String sessionId, int historyMessages) {
        SessionRuntimeState base = TestRuntimeStates.basic(sessionId);
        List<AgentMessage> transcript = new ArrayList<>();
        for (int index = 0; index < historyMessages; index++) {
            transcript.add(message(
                "history-" + index,
                MessageRole.ASSISTANT,
                MessageKind.TEXT,
                new TextContentBlock("new history " + index)
            ));
        }
        transcript.add(message(
            "read-call",
            MessageRole.ASSISTANT,
            MessageKind.TOOL_CALL,
            new ToolCallContentBlock("read-1", "read", "", Map.of("inputSummary", "read AGENTS.md"))
        ));
        transcript.add(message(
            "read-result",
            MessageRole.TOOL_RESULT,
            MessageKind.TOOL_RESULT,
            new ToolResultContentBlock("read-1", "read result summary", false)
        ));
        transcript.add(message(
            "bash-call",
            MessageRole.ASSISTANT,
            MessageKind.TOOL_CALL,
            new ToolCallContentBlock("bash-1", "bash", "", Map.of("inputSummary", "exit 1"))
        ));
        transcript.add(message(
            "bash-result",
            MessageRole.TOOL_RESULT,
            MessageKind.TOOL_RESULT,
            new ToolResultContentBlock("bash-1", "command failed", true, Map.of("status", "FAILED"))
        ));
        transcript.add(message(
            "final-answer",
            MessageRole.ASSISTANT,
            MessageKind.TEXT,
            new TextContentBlock("resume complete")
        ));
        return new SessionRuntimeState(
            base.sessionId(),
            base.cwd(),
            base.currentBranchLeafId(),
            base.model(),
            base.thinkingLevel(),
            base.agentMode(),
            base.permissionRuntimeState(),
            base.budget(),
            transcript,
            false,
            false,
            false,
            false
        );
    }

    private static SessionRuntimeState historyRuntimeState(String sessionId, int historyMessages) {
        SessionRuntimeState base = TestRuntimeStates.basic(sessionId);
        List<AgentMessage> transcript = java.util.stream.IntStream.rangeClosed(1, historyMessages)
            .mapToObj(index -> message(
                "history-line-" + index,
                MessageRole.ASSISTANT,
                MessageKind.TEXT,
                new TextContentBlock("history-line-" + index)
            ))
            .toList();
        return new SessionRuntimeState(
            base.sessionId(),
            base.cwd(),
            base.currentBranchLeafId(),
            base.model(),
            base.thinkingLevel(),
            base.agentMode(),
            base.permissionRuntimeState(),
            base.budget(),
            transcript,
            false,
            false,
            false,
            false
        );
    }

    private static AgentMessage message(
        String id,
        MessageRole role,
        MessageKind kind,
        cn.lypi.contracts.context.ContentBlock block
    ) {
        return new AgentMessage(
            id,
            role,
            kind,
            List.of(block),
            Instant.parse("2026-06-09T00:00:00Z"),
            Optional.empty(),
            Optional.empty()
        );
    }

    private static MessageDeltaEvent textDelta(String delta, boolean isFinal) {
        return new MessageDeltaEvent(
            "ses_1",
            "msg_stream",
            MessageRole.ASSISTANT,
            MessageKind.TEXT,
            "block_stream",
            ContentBlockKind.TEXT,
            delta,
            isFinal,
            Map.of(),
            Instant.parse("2026-06-09T00:00:00Z")
        );
    }

    private static TuiToolBlock tool(JLineTuiTransport transport, String toolUseId) {
        return transport.viewForTest().blocks().stream()
            .filter(TuiToolBlock.class::isInstance)
            .map(TuiToolBlock.class::cast)
            .filter(block -> toolUseId.equals(block.toolUseId()))
            .findFirst()
            .orElseThrow();
    }

    private static final class QueueInputSource implements TerminalInputSource {
        private final ArrayDeque<String> chunks;

        private QueueInputSource(String... chunks) {
            this.chunks = new ArrayDeque<>(List.of(chunks));
        }

        private synchronized void add(String chunk) {
            chunks.addLast(chunk);
        }

        @Override
        public synchronized Optional<String> read() {
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

    private static boolean awaitFrame(List<List<String>> frames, Predicate<List<String>> predicate)
        throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            if (frames.stream().anyMatch(predicate)) {
                return true;
            }
            Thread.sleep(5L);
        }
        return frames.stream().anyMatch(predicate);
    }

    private static long transcriptRows(List<String> frame) {
        return frame.stream().filter(line -> line.startsWith("line ")).count();
    }

    private static final class RecordingSubmitHandler implements TuiSubmitHandler {
        private final List<String> interruptReasons = new ArrayList<>();
        private int interrupts;
        private int exits;

        @Override
        public void submitUserInput(String input) {
        }

        @Override
        public void requestInterrupt(String reason) {
            interrupts++;
            interruptReasons.add(reason);
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

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }

        void advanceSeconds(long seconds) {
            current = current.plusSeconds(seconds);
        }
    }
}
