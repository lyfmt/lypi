package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.agent.TurnRequest;
import cn.lypi.contracts.agent.TurnState;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.EventConsumer;
import cn.lypi.contracts.event.EventEnvelope;
import cn.lypi.contracts.event.EventFilter;
import cn.lypi.contracts.event.EventSubscription;
import cn.lypi.contracts.event.ErrorEvent;
import cn.lypi.contracts.event.MessageDeltaEvent;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.resource.ResourceSnapshot;
import cn.lypi.contracts.runtime.AgentCorePort;
import cn.lypi.contracts.runtime.ResourceRuntimePort;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.session.ForkRequest;
import cn.lypi.contracts.session.SessionContext;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.session.SessionView;
import cn.lypi.contracts.session.ThinkingChangeEntry;
import cn.lypi.contracts.tui.DiffView;
import cn.lypi.contracts.tui.DiffViewProvider;
import cn.lypi.contracts.tui.GitDiffFileView;
import cn.lypi.contracts.tui.GitDiffStatus;
import cn.lypi.contracts.tui.ResumeSessionController;
import cn.lypi.contracts.tui.SessionBranchTreeView;
import cn.lypi.contracts.tui.SessionResumeInfo;
import cn.lypi.contracts.tui.SessionTreeNodeView;
import cn.lypi.contracts.tui.SessionRuntimeState;
import cn.lypi.contracts.tui.SlashCommand;
import cn.lypi.contracts.tui.SlashCommandHandler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JLineTuiTransportTest {
    private static final DiffViewProvider NOOP_DIFF_PROVIDER = (cwd, maxPatchBytes) -> Optional.empty();

    @Test
    void attachSubscribesToSessionEventsAndRendersUnderUiLock() {
        RecordingScreen screen = new RecordingScreen();
        RecordingEventBus events = new RecordingEventBus();
        JLineTuiTransport transport = new JLineTuiTransport(screen::render);

        transport.attach(events, runtimeState());
        events.emit(new ErrorEvent("ses_1", "err_1", "boom", Instant.parse("2026-06-09T00:00:00Z")));

        assertTrue(events.subscribed);
        assertEquals(1, screen.renderCount);
        assertTrue(transport.isUiLockedForTest());
    }

    @Test
    void attachIgnoresEventsFromOtherSessions() {
        RecordingScreen screen = new RecordingScreen();
        RecordingEventBus events = new RecordingEventBus();
        JLineTuiTransport transport = new JLineTuiTransport(screen::render);

        transport.attach(events, runtimeState());
        events.emit(new ErrorEvent("other_session", "err_1", "boom", Instant.parse("2026-06-09T00:00:00Z")));

        assertEquals(0, screen.renderCount);
    }

    @Test
    void openAssemblesTerminalSessionRendererInputAndEventSubscription() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        io.height = 5;
        RecordingEventBus events = new RecordingEventBus();

        JLineTuiTransport transport = JLineTuiTransport.open(
            runtimeState(),
            events,
            io,
            () -> Optional.empty(),
            new RecordingSubmitHandler(),
            40,
            5
        );

        events.emit(new ErrorEvent("ses_1", "err_1", "boom", Instant.parse("2026-06-09T00:00:00Z")));

        assertTrue(io.rawModeEntered);
        assertFalse(io.output.toString().contains("\033[?1049h"));
        assertTrue(io.output.toString().contains("\033[?2026h\033[1;1H\033[2Kerror: boom"));

        transport.close();

        assertTrue(io.rawModeRestored);
        assertFalse(io.output.toString().contains("\033[?1049l"));
    }

    @Test
    void openRendersInitialFrameFromRuntimeState() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        RecordingEventBus events = new RecordingEventBus();

        JLineTuiTransport transport = JLineTuiTransport.open(
            runtimeState(),
            events,
            io,
            () -> Optional.empty(),
            new RecordingSubmitHandler(),
            120,
            4
        );

        String frame = io.output.toString();
        assertFalse(frame.contains("\033[H\033[J"));
        assertTrue(frame.contains("ses_1 gpt-5.4 EXECUTE DEFAULT_EXECUTE"));
        assertTrue(frame.contains("> "));

        transport.close();
    }

    @Test
    void openPipelineKeepsLongTranscriptInOutputStreamWithoutAlternateScreenOrRepeatedHomeClear() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        io.height = 5;
        RecordingEventBus events = new RecordingEventBus();

        JLineTuiTransport transport = JLineTuiTransport.open(
            runtimeState(),
            events,
            io,
            () -> Optional.empty(),
            new RecordingSubmitHandler(),
            80,
            5
        );

        for (int i = 0; i < 8; i++) {
            events.emit(new MessageDeltaEvent(
                "ses_1",
                "msg_" + i,
                cn.lypi.contracts.context.MessageRole.ASSISTANT,
                cn.lypi.contracts.context.MessageKind.TEXT,
                "block_" + i,
                cn.lypi.contracts.context.ContentBlockKind.TEXT,
                "line " + i,
                true,
                Map.of(),
                Instant.parse("2026-06-09T00:00:00Z")
            ));
        }

        String output = io.output.toString();
        assertTrue(output.contains("line 0"));
        assertTrue(output.contains("line 7"));
        assertFalse(output.contains("\033[?1049h"));
        assertFalse(output.contains("\033[?1049l"));
        assertFalse(output.contains("\033[H\033[J"));

        transport.close();
    }

    @Test
    void openPipelineDoesNotAppendBottomChromeToScrollbackDuringOverflow() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        io.height = 4;
        RecordingEventBus events = new RecordingEventBus();

        JLineTuiTransport transport = JLineTuiTransport.open(
            runtimeState(),
            events,
            io,
            () -> Optional.empty(),
            new RecordingSubmitHandler(),
            80,
            4
        );
        io.output.setLength(0);

        for (int i = 0; i < 4; i++) {
            events.emit(new MessageDeltaEvent(
                "ses_1",
                "msg_" + i,
                cn.lypi.contracts.context.MessageRole.ASSISTANT,
                cn.lypi.contracts.context.MessageKind.TEXT,
                "block_" + i,
                cn.lypi.contracts.context.ContentBlockKind.TEXT,
                "line " + i,
                true,
                Map.of(),
                Instant.parse("2026-06-09T00:00:00Z")
            ));
        }

        String output = io.output.toString();
        assertTrue(output.contains("\r\n"));
        assertTrue(output.contains("\033[?2026h"));
        assertTrue(output.contains("\033[1;"));
        assertTrue(output.contains("\033[2K"));
        assertFalse(output.contains("\r\n\033[2K\033[38;5;240m"));
        assertFalse(output.contains("\r\n\033[2K\033[48;5;236m> "));
        assertFalse(output.contains("\r\n\033[2Kses_1 gpt-5.4"));

        transport.close();
    }

    @Test
    void closeAfterOverflowMovesPromptBelowPhysicalViewport() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        io.height = 3;
        RecordingEventBus events = new RecordingEventBus();

        JLineTuiTransport transport = JLineTuiTransport.open(
            runtimeState(),
            events,
            io,
            () -> Optional.empty(),
            new RecordingSubmitHandler(),
            80,
            3
        );

        for (int i = 0; i < 6; i++) {
            events.emit(new MessageDeltaEvent(
                "ses_1",
                "msg_" + i,
                cn.lypi.contracts.context.MessageRole.ASSISTANT,
                cn.lypi.contracts.context.MessageKind.TEXT,
                "block_" + i,
                cn.lypi.contracts.context.ContentBlockKind.TEXT,
                "line " + i,
                true,
                Map.of(),
                Instant.parse("2026-06-09T00:00:00Z")
            ));
        }
        io.output.setLength(0);

        transport.close();

        assertTrue(io.output.toString().endsWith("\033[3;1H\n"));
    }

    @Test
    void toolEndRefreshesDiffViewFromProvider() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        RecordingEventBus events = new RecordingEventBus();
        RecordingDiffProvider diffProvider = new RecordingDiffProvider(Optional.of(new DiffView(
            "1 file changed",
            List.of(new GitDiffFileView(Path.of("src/App.java"), GitDiffStatus.MODIFIED, "Modified", Map.of())),
            "+new line",
            false,
            Map.of("snapshotHash", "sha256:1")
        )));
        io.height = 8;

        JLineTuiTransport transport = JLineTuiTransport.open(
            runtimeState(),
            events,
            io,
            () -> Optional.empty(),
            new RecordingSubmitHandler(),
            diffProvider,
            80,
            8
        );
        io.output.setLength(0);

        events.emit(new cn.lypi.contracts.event.ToolEndEvent(
            "ses_1",
            "toolu_1",
            false,
            Instant.parse("2026-06-09T00:00:00Z")
        ));

        assertEquals(1, diffProvider.calls);
        assertEquals(Path.of("."), diffProvider.cwd);
        assertTrue(io.output.toString().contains("diff: 1 file changed"));
        assertTrue(io.output.toString().contains("M src/App.java"));
        assertTrue(io.output.toString().contains("+new line"));

        transport.close();
    }

    @Test
    void openClosesTerminalSessionWhenInitialFrameRenderFails() {
        FailingInitialFrameTerminalIo io = new FailingInitialFrameTerminalIo();
        RecordingEventBus events = new RecordingEventBus();

        assertThrows(UncheckedIOException.class, () -> JLineTuiTransport.open(
            runtimeState(),
            events,
            io,
            () -> Optional.empty(),
            new RecordingSubmitHandler(),
            40,
            4
        ));

        assertTrue(io.rawModeRestored);
        assertFalse(io.output.toString().contains("\033[?1049l"));
    }

    @Test
    void openResizeCallbackReadsCurrentTerminalDimensions() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        RecordingEventBus events = new RecordingEventBus();

        JLineTuiTransport transport = JLineTuiTransport.open(
            runtimeState(),
            events,
            io,
            () -> Optional.empty(),
            new RecordingSubmitHandler(),
            40,
            4
        );
        io.output.setLength(0);

        io.width = 12;
        io.height = 6;
        io.resizeCallback.run();

        String frame = io.output.toString();
        String fullClear = "\033[2J\033[H";
        String rendered = frame.substring(frame.indexOf(fullClear) + fullClear.length(), frame.indexOf("\033[?2026l"));
        assertEquals(6, rendered.split("\n", -1).length);
        assertTrue(rendered.contains("> "));

        transport.close();
    }

    @Test
    void openWithSlashCommandsExecutesSlashInputWithoutStartingTurn() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        RecordingEventBus events = new RecordingEventBus();
        RecordingCore core = new RecordingCore();
        RecordingSlashCommandHandler slash = new RecordingSlashCommandHandler("mailId: mail_1");

        JLineTuiTransport transport = JLineTuiTransport.open(
            runtimeState(),
            core,
            events,
            io,
            new QueueInputSource("/mailbox accept mail_1", "\r"),
            Runnable::run,
            List.of(new SlashCommand("mailbox", "读取 mailbox", List.of(), slash)),
            40,
            4
        );

        transport.drainInputForTest();

        assertTrue(core.requests.isEmpty());
        assertEquals(Map.of("action", "accept", "mailId", "mail_1"), slash.arguments);
        MessageDeltaEvent delta = assertInstanceOf(MessageDeltaEvent.class, events.published.get(1));
        assertEquals("mailId: mail_1", delta.delta());

        transport.close();
    }

    @Test
    void openFallsBackWhenTerminalSizeUnavailable() {
        RecordingTerminalIo io = new RecordingTerminalIo();
        RecordingEventBus events = new RecordingEventBus();

        assertDoesNotThrow(() -> {
            JLineTuiTransport transport = JLineTuiTransport.open(
                runtimeState(),
                events,
                io,
                () -> Optional.empty(),
                new RecordingSubmitHandler(),
                0,
                0
            );
            transport.close();
        });
    }

    @Test
    void openWithRuntimePortsRoutesSlashCommandsBeforeCoreSubmission() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        RecordingEventBus events = new RecordingEventBus();
        RecordingCore core = new RecordingCore();
        RecordingSessionManager session = new RecordingSessionManager();

        JLineTuiTransport transport = JLineTuiTransport.open(
            runtimeState(),
            core,
            events,
            io,
            new QueueInputSource("/thinking high", "\n"),
            session,
            emptyResources(),
            40,
            4
        );

        transport.drainInputForTest();

        assertEquals(0, core.requests.size());
        ThinkingChangeEntry entry = assertInstanceOf(ThinkingChangeEntry.class, session.entries.getFirst());
        assertEquals(ThinkingLevel.HIGH, entry.thinkingLevel());

        transport.close();
    }

    @Test
    void resumeRuntimeStateRebindsEventSubscriptionToResumedSession() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        io.width = 80;
        io.height = 12;
        RecordingEventBus events = new RecordingEventBus();

        JLineTuiTransport transport = JLineTuiTransport.open(
            runtimeState(),
            events,
            io,
            new QueueInputSource("/resume", "\r", "\r", "\r"),
            new RecordingSubmitHandler(),
            () -> new SlashCommandPicker(List.of("/resume")),
            NOOP_DIFF_PROVIDER,
            resumeControllerReturning(runtimeStateWithTranscript("ses_old", "leaf_old", "restored context")),
            80,
            8
        );
        io.output.setLength(0);

        transport.drainInputForTest();
        transport.renderCurrentFrameUnderUiLock();
        events.emit(new ErrorEvent("ses_1", "err_old", "old", Instant.parse("2026-06-09T00:00:00Z")));
        events.emit(new ErrorEvent("ses_old", "err_new", "new", Instant.parse("2026-06-09T00:00:00Z")));

        assertEquals(Optional.of("ses_old"), events.filter.sessionId());
        assertFalse(io.output.toString().contains("error: old"));
        assertTrue(io.output.toString().contains("restored context"));

        transport.close();
    }

    @Test
    void newCommandRebindsEventSubscriptionToNewSession() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        io.width = 80;
        io.height = 12;
        RecordingEventBus events = new RecordingEventBus();
        RecordingCore core = new RecordingCore();
        RecordingSessionManager session = new RecordingSessionManager();
        SessionRuntimeState newState = runtimeStateWithTranscript("ses_new", "leaf_new", "new session context");

        JLineTuiTransport transport = JLineTuiTransport.open(
            runtimeState(),
            core,
            events,
            io,
            new QueueInputSource("/new", "\r", "\r"),
            List.of(),
            session,
            emptyResources(),
            null,
            NOOP_DIFF_PROVIDER,
            null,
            () -> newState,
            80,
            8
        );
        io.output.setLength(0);

        transport.drainInputForTest();
        transport.renderCurrentFrameUnderUiLock();
        events.emit(new ErrorEvent("ses_1", "err_old", "old", Instant.parse("2026-06-09T00:00:00Z")));
        events.emit(new ErrorEvent("ses_new", "err_new", "new", Instant.parse("2026-06-09T00:00:00Z")));

        assertEquals(Optional.of("ses_new"), events.filter.sessionId());
        assertEquals(0, core.requests.size());
        assertFalse(io.output.toString().contains("error: old"));
        assertTrue(io.output.toString().contains("new session context"));

        transport.close();
    }

    @Test
    void resizeFallsBackWhenTerminalSizeUnavailable() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        RecordingEventBus events = new RecordingEventBus();
        JLineTuiTransport transport = JLineTuiTransport.open(
            runtimeState(),
            events,
            io,
            () -> Optional.empty(),
            new RecordingSubmitHandler(),
            40,
            4
        );

        io.width = 0;
        io.height = 0;

        assertDoesNotThrow(() -> io.resizeCallback.run());
        transport.close();
    }

    @Test
    void withInputFallsBackWhenTerminalSizeUnavailable() {
        assertDoesNotThrow(() -> JLineTuiTransport.withInput(
            ignored -> {
            },
            0,
            0,
            () -> Optional.of("draft"),
            new RecordingSubmitHandler()
        ).drainInputForTest());
    }

    @Test
    void layoutStillRejectsInvalidDimensions() {
        assertThrows(IllegalArgumentException.class, () -> new TuiLayout(0, 0));
    }

    private SessionRuntimeState runtimeState() {
        return runtimeState("ses_1", "leaf_1");
    }

    private SessionRuntimeState runtimeState(String sessionId, String leafId) {
        return new SessionRuntimeState(
            sessionId,
            Path.of("."),
            leafId,
            new ModelSelection("openai", "gpt-5.4", ThinkingLevel.HIGH),
            ThinkingLevel.HIGH,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE,
            new ContextBudget(0, 200000, 180000, 12000, 6000, 0, 0, BigDecimal.ZERO),
            false,
            false,
            false,
            false
        );
    }

    private SessionRuntimeState runtimeStateWithTranscript(String sessionId, String leafId, String content) {
        return new SessionRuntimeState(
            sessionId,
            Path.of("."),
            leafId,
            new ModelSelection("openai", "gpt-5.4", ThinkingLevel.HIGH),
            ThinkingLevel.HIGH,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE,
            new ContextBudget(0, 200000, 180000, 12000, 6000, 0, 0, BigDecimal.ZERO),
            List.of(new AgentMessage(
                "msg_restored",
                MessageRole.USER,
                MessageKind.TEXT,
                List.of(new TextContentBlock(content)),
                Instant.parse("2026-06-09T00:00:00Z"),
                Optional.empty(),
                Optional.empty()
            )),
            false,
            false,
            false,
            false
        );
    }

    private static ResumeSessionController resumeControllerReturning(SessionRuntimeState state) {
        return new ResumeSessionController() {
            @Override
            public List<SessionResumeInfo> sessions() {
                return List.of(new SessionResumeInfo(
                    Path.of("old.jsonl"),
                    state.sessionId(),
                    Path.of("."),
                    Optional.empty(),
                    state.currentBranchLeafId(),
                    Instant.EPOCH,
                    Instant.EPOCH,
                    1,
                    "old prompt",
                    "old prompt"
                ));
            }

            @Override
            public SessionBranchTreeView tree(String sessionId) {
                SessionEntry entry = new cn.lypi.contracts.session.CustomMessageEntry(
                    state.currentBranchLeafId(),
                    null,
                    "old prompt",
                    Instant.EPOCH
                );
                return new SessionBranchTreeView(sessionId, state.currentBranchLeafId(), List.of(new SessionTreeNodeView(entry, List.of())));
            }

            @Override
            public SessionRuntimeState resume(String sessionId, String leafId) {
                return state;
            }
        };
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
        private final List<AgentEvent> published = new ArrayList<>();

        @Override
        public void publish(AgentEvent event) {
            published.add(event);
        }

        @Override
        public EventSubscription subscribe(EventFilter filter, EventConsumer consumer) {
            assertTrue(filter.sessionId().isPresent());
            assertTrue(filter.eventType().isEmpty());
            this.consumer = consumer;
            this.filter = filter;
            subscribed = true;
            return () -> {
            };
        }

        void emit(AgentEvent event) {
            if (filter.sessionId().isPresent() && !filter.sessionId().orElseThrow().equals(event.sessionId())) {
                return;
            }
            consumer.accept(new EventEnvelope("evt_1", "ses_1", 1, event));
        }

        private EventFilter filter;
    }

    private static ResourceRuntimePort emptyResources() {
        return new ResourceRuntimePort() {
            @Override
            public ResourceSnapshot load(Path cwd) {
                return new ResourceSnapshot(List.of(), List.of(), new cn.lypi.contracts.skill.SkillIndex(List.of(), List.of()), List.of(), List.of(), List.of());
            }

            @Override
            public cn.lypi.contracts.prompt.SystemPrompt buildSystemPrompt(ResourceSnapshot resources) {
                return null;
            }
        };
    }

    private static final class RecordingSessionManager implements SessionManagerPort {
        private final List<SessionEntry> entries = new ArrayList<>();
        private String leafId = "root";

        @Override
        public SessionHandle openOrCreate(String sessionId) {
            return new SessionHandle(sessionId, Path.of("session.jsonl"), leafId, Map.of());
        }

        @Override
        public SessionHandle append(SessionEntry entry) {
            entries.add(entry);
            leafId = entry.id();
            return openOrCreate("ses_1");
        }

        @Override
        public SessionHandle switchLeaf(String leafId) {
            this.leafId = leafId;
            return openOrCreate("ses_1");
        }

        @Override
        public List<SessionEntry> branch(String leafId) {
            return List.copyOf(entries);
        }

        @Override
        public SessionView currentView() {
            return new SessionView("ses_1", leafId);
        }

        @Override
        public SessionView view(String leafId) {
            return new SessionView("ses_1", leafId);
        }

        @Override
        public List<AgentMessage> transcript(String leafId) {
            return List.of();
        }

        @Override
        public SessionContext context(String leafId) {
            return new SessionContext(
                List.of(),
                List.of(this.leafId),
                List.of(),
                new ModelSelection("openai", "gpt-5", ThinkingLevel.MEDIUM),
                ThinkingLevel.MEDIUM,
                AgentMode.EXECUTE,
                PermissionMode.DEFAULT_EXECUTE
            );
        }

        @Override
        public SessionHandle appendMessage(AgentMessage message) {
            return openOrCreate("ses_1");
        }

        @Override
        public SessionHandle fork(ForkRequest request) {
            return openOrCreate("ses_1");
        }
    }

    private static final class RecordingSubmitHandler implements TuiSubmitHandler {
        @Override
        public void submitUserInput(String input) {
        }

        @Override
        public void requestInterrupt(String reason) {
        }
    }

    private static final class RecordingCore implements AgentCorePort {
        private final List<TurnRequest> requests = new ArrayList<>();

        @Override
        public TurnState execute(TurnRequest request) {
            requests.add(request);
            return null;
        }
    }

    private static final class RecordingSlashCommandHandler implements SlashCommandHandler {
        private final String output;
        private Map<String, String> arguments;

        private RecordingSlashCommandHandler(String output) {
            this.output = output;
        }

        @Override
        public void handle(Map<String, String> arguments) {
            this.arguments = arguments;
        }

        @Override
        public String lastOutput() {
            return output;
        }
    }

    private static final class RecordingDiffProvider implements DiffViewProvider {
        private final Optional<DiffView> view;
        private int calls;
        private Path cwd;

        private RecordingDiffProvider(Optional<DiffView> view) {
            this.view = view;
        }

        @Override
        public Optional<DiffView> currentDiff(Path cwd, int maxPatchBytes) {
            calls++;
            this.cwd = cwd;
            return view;
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

    private static final class RecordingTerminalIo implements TerminalIo {
        private final StringBuilder output = new StringBuilder();
        private boolean rawModeEntered;
        private boolean rawModeRestored;
        private int width = 40;
        private int height = 4;
        private Runnable resizeCallback = () -> {
        };

        @Override
        public AutoCloseable enterRawMode() {
            rawModeEntered = true;
            return () -> rawModeRestored = true;
        }

        @Override
        public void write(String value) {
            output.append(value);
        }

        @Override
        public void flush() {
        }

        @Override
        public AutoCloseable onResize(Runnable callback) throws IOException {
            resizeCallback = callback;
            return () -> {
            };
        }

        @Override
        public AutoCloseable onInterrupt(Runnable callback) {
            return () -> {
            };
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }
    }

    private static final class FailingInitialFrameTerminalIo implements TerminalIo {
        private final StringBuilder output = new StringBuilder();
        private boolean rawModeRestored;
        private int writesUntilFailure = 4;

        @Override
        public AutoCloseable enterRawMode() {
            return () -> rawModeRestored = true;
        }

        @Override
        public void write(String value) throws IOException {
            if (writesUntilFailure == 0) {
                writesUntilFailure--;
                throw new IOException("initial frame failed");
            }
            writesUntilFailure--;
            output.append(value);
        }

        @Override
        public void flush() {
        }

        @Override
        public AutoCloseable onResize(Runnable callback) {
            return () -> {
            };
        }

        @Override
        public AutoCloseable onInterrupt(Runnable callback) {
            return () -> {
            };
        }

        @Override
        public int width() {
            return 40;
        }

        @Override
        public int height() {
            return 4;
        }
    }
}
