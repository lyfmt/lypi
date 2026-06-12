package cn.lypi.transport.tui;

import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.EventFilter;
import cn.lypi.contracts.event.EventSubscription;
import cn.lypi.contracts.event.ToolEndEvent;
import cn.lypi.contracts.runtime.AgentCorePort;
import cn.lypi.contracts.runtime.CompactionRuntimePort;
import cn.lypi.contracts.runtime.ResourceRuntimePort;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.skill.SkillIndex;
import cn.lypi.contracts.tui.DiffView;
import cn.lypi.contracts.tui.DiffViewProvider;
import cn.lypi.contracts.tui.NewSessionController;
import cn.lypi.contracts.tui.ResumeSessionController;
import cn.lypi.contracts.tui.SessionRuntimeState;
import cn.lypi.contracts.tui.SlashCommand;
import cn.lypi.contracts.tui.TuiToolBlock;
import cn.lypi.contracts.tui.TuiViewModel;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.jline.terminal.Terminal;

public final class JLineTuiTransport implements TuiTransport, AutoCloseable {
    private static final int DEFAULT_TERMINAL_WIDTH = 80;
    private static final int DEFAULT_TERMINAL_HEIGHT = 24;
    private static final int MAX_INPUT_CHUNKS_PER_DRAIN = 32;
    private static final int MAX_DIFF_PATCH_BYTES = 64 * 1024;
    private static final DiffViewProvider NOOP_DIFF_VIEW_PROVIDER = (cwd, maxPatchBytes) -> Optional.empty();

    private final Object uiMonitor = new Object();
    private final Runnable renderer;
    private final TuiEventReducer reducer;
    private final TuiRenderer tuiRenderer;
    private TuiScreen screen;
    private TuiLayout layout;
    private final FrameSink frameSink;
    private final TerminalInputPump inputPump;
    private final TuiInputLoop inputLoop;
    private final TerminalSession terminalSession;
    private final DiffViewProvider diffViewProvider;
    private SessionRuntimeState runtimeState;
    private String lastDiffSnapshotHash;
    private EventSubscription subscription;
    private EventBus attachedEvents;
    private boolean lastRenderHeldUiLock;
    private int uiLockEntries;

    public JLineTuiTransport(Runnable renderer) {
        this.renderer = renderer;
        this.reducer = null;
        this.tuiRenderer = null;
        this.screen = null;
        this.layout = null;
        this.frameSink = null;
        this.inputPump = null;
        this.inputLoop = null;
        this.terminalSession = null;
        this.diffViewProvider = NOOP_DIFF_VIEW_PROVIDER;
        this.runtimeState = null;
    }

    private JLineTuiTransport(FrameSink frameSink, int width, int height) {
        this(frameSink, width, height, null);
    }

    private JLineTuiTransport(FrameSink frameSink, int width, int height, TerminalSession terminalSession) {
        this.renderer = null;
        this.reducer = new TuiEventReducer();
        this.tuiRenderer = new TuiRenderer();
        int safeWidth = safeWidth(width);
        int safeHeight = safeHeight(height);
        this.screen = new TuiScreen(Math.max(1, safeHeight - 2));
        this.layout = new TuiLayout(safeWidth, safeHeight);
        this.frameSink = frameSink;
        this.inputPump = null;
        this.inputLoop = null;
        this.terminalSession = terminalSession;
        this.diffViewProvider = NOOP_DIFF_VIEW_PROVIDER;
        this.runtimeState = null;
    }

    private JLineTuiTransport(
        FrameSink frameSink,
        int width,
        int height,
        SessionRuntimeState state,
        TerminalInputSource inputSource,
        TuiSubmitHandler submitHandler,
        TerminalSession terminalSession,
        Supplier<SlashCommandPicker> slashPickerSupplier,
        DiffViewProvider diffViewProvider,
        ResumeSessionController resumeController,
        Supplier<SkillIndex> skillIndexSupplier
    ) {
        this.renderer = null;
        this.reducer = TuiEventReducer.fromRuntimeState(state);
        this.tuiRenderer = new TuiRenderer();
        int safeWidth = safeWidth(width);
        int safeHeight = safeHeight(height);
        this.screen = new TuiScreen(Math.max(1, safeHeight - 2));
        this.layout = new TuiLayout(safeWidth, safeHeight);
        this.frameSink = frameSink;
        this.inputLoop = new TuiInputLoop(
            submitHandler,
            frameSink,
            tuiRenderer,
            screen,
            layout,
            reducer::view,
            slashPickerSupplier,
            resumeController,
            this::resumeRuntimeState,
            skillIndexSupplier
        );
        this.inputPump = new TerminalInputPump(inputSource, new KeyMapper(), inputLoop);
        this.terminalSession = terminalSession;
        this.diffViewProvider = diffViewProvider == null ? NOOP_DIFF_VIEW_PROVIDER : diffViewProvider;
        this.runtimeState = state;
    }

    /**
     * 打开真实 JLine TUI transport 并挂载事件流。
     */
    public static JLineTuiTransport open(
        SessionRuntimeState state,
        AgentCorePort core,
        EventBus events,
        Terminal terminal
    ) throws IOException {
        return open(state, core, events, terminal, List.of());
    }

    /**
     * 打开带 slash command 支持的真实 JLine TUI transport。
     */
    public static JLineTuiTransport open(
        SessionRuntimeState state,
        AgentCorePort core,
        EventBus events,
        Terminal terminal,
        List<SlashCommand> slashCommands
    ) throws IOException {
        return open(state, core, events, terminal, NOOP_DIFF_VIEW_PROVIDER, null, slashCommands);
    }

    /**
     * 打开带 slash command 和 diff provider 支持的真实 JLine TUI transport。
     */
    public static JLineTuiTransport open(
        SessionRuntimeState state,
        AgentCorePort core,
        EventBus events,
        Terminal terminal,
        DiffViewProvider diffViewProvider,
        List<SlashCommand> slashCommands
    ) throws IOException {
        return open(state, core, events, terminal, diffViewProvider, null, slashCommands);
    }

    /**
     * 打开带 slash command、diff provider 和 resume controller 支持的真实 JLine TUI transport。
     */
    public static JLineTuiTransport open(
        SessionRuntimeState state,
        AgentCorePort core,
        EventBus events,
        Terminal terminal,
        DiffViewProvider diffViewProvider,
        ResumeSessionController resumeController,
        List<SlashCommand> slashCommands
    ) throws IOException {
        JLineTerminalIo io = new JLineTerminalIo(terminal);
        return open(
            state,
            core,
            events,
            io,
            new JLineTerminalInputSource(terminal),
            command -> Thread.ofVirtual().name("lypi-tui-turn-", 0).start(command),
            slashCommands,
            diffViewProvider,
            resumeController,
            terminal.getWidth(),
            terminal.getHeight()
        );
    }

    static JLineTuiTransport open(
        SessionRuntimeState state,
        AgentCorePort core,
        EventBus events,
        TerminalIo io,
        TerminalInputSource inputSource,
        java.util.concurrent.Executor executor,
        List<SlashCommand> slashCommands,
        int width,
        int height
    ) throws IOException {
        return open(state, core, events, io, inputSource, executor, slashCommands, NOOP_DIFF_VIEW_PROVIDER, null, width, height);
    }

    static JLineTuiTransport open(
        SessionRuntimeState state,
        AgentCorePort core,
        EventBus events,
        TerminalIo io,
        TerminalInputSource inputSource,
        java.util.concurrent.Executor executor,
        List<SlashCommand> slashCommands,
        DiffViewProvider diffViewProvider,
        ResumeSessionController resumeController,
        int width,
        int height
    ) throws IOException {
        return open(
            state,
            events,
            io,
            inputSource,
            new RuntimeTuiSubmitHandler(state.sessionId(), core, events, executor, slashCommands),
            null,
            diffViewProvider,
            resumeController,
            width,
            height
        );
    }

    /**
     * 打开真实 JLine TUI transport，并在提交前启用 slash command 路由。
     */
    public static JLineTuiTransport open(
        SessionRuntimeState state,
        AgentCorePort core,
        EventBus events,
        Terminal terminal,
        SessionManagerPort sessionManager,
        ResourceRuntimePort resourceRuntime,
        CompactionRuntimePort compactionRuntime
    ) throws IOException {
        return open(state, core, events, terminal, List.of(), sessionManager, resourceRuntime, compactionRuntime);
    }

    /**
     * 打开真实 JLine TUI transport，并在提交前启用内建和外部 slash command 路由。
     */
    public static JLineTuiTransport open(
        SessionRuntimeState state,
        AgentCorePort core,
        EventBus events,
        Terminal terminal,
        List<SlashCommand> slashCommands,
        SessionManagerPort sessionManager,
        ResourceRuntimePort resourceRuntime,
        CompactionRuntimePort compactionRuntime
    ) throws IOException {
        return open(
            state,
            core,
            events,
            terminal,
            NOOP_DIFF_VIEW_PROVIDER,
            slashCommands,
            sessionManager,
            resourceRuntime,
            compactionRuntime
        );
    }

    /**
     * 打开真实 JLine TUI transport，并启用 slash command 路由和 diff provider。
     */
    public static JLineTuiTransport open(
        SessionRuntimeState state,
        AgentCorePort core,
        EventBus events,
        Terminal terminal,
        DiffViewProvider diffViewProvider,
        List<SlashCommand> slashCommands,
        SessionManagerPort sessionManager,
        ResourceRuntimePort resourceRuntime,
        CompactionRuntimePort compactionRuntime
    ) throws IOException {
        return open(
            state,
            core,
            events,
            terminal,
            diffViewProvider,
            slashCommands,
            null,
            sessionManager,
            resourceRuntime,
            compactionRuntime
        );
    }

    /**
     * 打开真实 JLine TUI transport，并启用 slash command 路由、diff provider 和 resume controller。
     */
    public static JLineTuiTransport open(
        SessionRuntimeState state,
        AgentCorePort core,
        EventBus events,
        Terminal terminal,
        DiffViewProvider diffViewProvider,
        List<SlashCommand> slashCommands,
        ResumeSessionController resumeController,
        SessionManagerPort sessionManager,
        ResourceRuntimePort resourceRuntime,
        CompactionRuntimePort compactionRuntime
    ) throws IOException {
        return open(
            state,
            core,
            events,
            terminal,
            diffViewProvider,
            slashCommands,
            resumeController,
            null,
            sessionManager,
            resourceRuntime,
            compactionRuntime
        );
    }

    /**
     * 打开真实 JLine TUI transport，并启用 slash command 路由、resume 和 /new 控制器。
     */
    public static JLineTuiTransport open(
        SessionRuntimeState state,
        AgentCorePort core,
        EventBus events,
        Terminal terminal,
        DiffViewProvider diffViewProvider,
        List<SlashCommand> slashCommands,
        ResumeSessionController resumeController,
        NewSessionController newSessionController,
        SessionManagerPort sessionManager,
        ResourceRuntimePort resourceRuntime,
        CompactionRuntimePort compactionRuntime
    ) throws IOException {
        JLineTerminalIo io = new JLineTerminalIo(terminal);
        return open(
            state,
            core,
            events,
            io,
            new JLineTerminalInputSource(terminal),
            slashCommands,
            sessionManager,
            resourceRuntime,
            compactionRuntime,
            diffViewProvider,
            resumeController,
            newSessionController,
            terminal.getWidth(),
            terminal.getHeight()
        );
    }

    public static JLineTuiTransport open(
        SessionRuntimeState state,
        AgentCorePort core,
        EventBus events,
        Terminal terminal,
        SessionManagerPort sessionManager,
        ResourceRuntimePort resourceRuntime
    ) throws IOException {
        return open(state, core, events, terminal, sessionManager, resourceRuntime, null);
    }

    static JLineTuiTransport open(
        SessionRuntimeState state,
        AgentCorePort core,
        EventBus events,
        TerminalIo io,
        TerminalInputSource inputSource,
        List<SlashCommand> slashCommands,
        SessionManagerPort sessionManager,
        ResourceRuntimePort resourceRuntime,
        CompactionRuntimePort compactionRuntime,
        int width,
        int height
    ) throws IOException {
        return open(
            state,
            core,
            events,
            io,
            inputSource,
            slashCommands,
            sessionManager,
            resourceRuntime,
            compactionRuntime,
            NOOP_DIFF_VIEW_PROVIDER,
            null,
            width,
            height
        );
    }

    static JLineTuiTransport open(
        SessionRuntimeState state,
        AgentCorePort core,
        EventBus events,
        TerminalIo io,
        TerminalInputSource inputSource,
        List<SlashCommand> slashCommands,
        SessionManagerPort sessionManager,
        ResourceRuntimePort resourceRuntime,
        CompactionRuntimePort compactionRuntime,
        DiffViewProvider diffViewProvider,
        ResumeSessionController resumeController,
        int width,
        int height
    ) throws IOException {
        return open(
            state,
            core,
            events,
            io,
            inputSource,
            slashCommands,
            sessionManager,
            resourceRuntime,
            compactionRuntime,
            diffViewProvider,
            resumeController,
            null,
            width,
            height
        );
    }

    static JLineTuiTransport open(
        SessionRuntimeState state,
        AgentCorePort core,
        EventBus events,
        TerminalIo io,
        TerminalInputSource inputSource,
        List<SlashCommand> slashCommands,
        SessionManagerPort sessionManager,
        ResourceRuntimePort resourceRuntime,
        CompactionRuntimePort compactionRuntime,
        DiffViewProvider diffViewProvider,
        ResumeSessionController resumeController,
        NewSessionController newSessionController,
        int width,
        int height
    ) throws IOException {
        SlashCommandRouter router = new SlashCommandRouter(
            state.sessionId(),
            state.cwd(),
            sessionManager,
            resourceRuntime,
            compactionRuntime,
            newSessionController,
            slashCommands
        );
        JLineTuiTransport[] holder = new JLineTuiTransport[1];
        RuntimeTuiSubmitHandler submitHandler = new RuntimeTuiSubmitHandler(
            state.sessionId(),
            core,
            events,
            command -> Thread.ofVirtual().name("lypi-tui-turn-", 0).start(command),
            router,
            runtimeState -> {
                if (holder[0] != null) {
                    holder[0].resumeRuntimeState(runtimeState);
                }
            }
        );
        JLineTuiTransport transport = open(
            state,
            events,
            io,
            inputSource,
            submitHandler,
            () -> new SlashCommandPicker(router.commandNames()),
            diffViewProvider,
            resumeController,
            () -> resourceRuntime.load(state.cwd()).skillIndex(),
            width,
            height
        );
        holder[0] = transport;
        return transport;
    }

    static JLineTuiTransport open(
        SessionRuntimeState state,
        AgentCorePort core,
        EventBus events,
        TerminalIo io,
        TerminalInputSource inputSource,
        SessionManagerPort sessionManager,
        ResourceRuntimePort resourceRuntime,
        int width,
        int height
    ) throws IOException {
        return open(state, core, events, io, inputSource, List.of(), sessionManager, resourceRuntime, null, width, height);
    }

    static JLineTuiTransport withRenderer(FrameSink frameSink, int width, int height) {
        return new JLineTuiTransport(frameSink, width, height);
    }

    static JLineTuiTransport withInput(
        FrameSink frameSink,
        int width,
        int height,
        TerminalInputSource inputSource,
        TuiSubmitHandler submitHandler
    ) {
        return new JLineTuiTransport(
            frameSink,
            width,
            height,
            null,
            inputSource,
            submitHandler,
            null,
            null,
            NOOP_DIFF_VIEW_PROVIDER,
            null,
            null
        );
    }

    static JLineTuiTransport open(
        SessionRuntimeState state,
        EventBus events,
        TerminalIo io,
        TerminalInputSource inputSource,
        TuiSubmitHandler submitHandler,
        Supplier<SlashCommandPicker> slashPickerSupplier,
        int width,
        int height
    ) throws IOException {
        return open(
            state,
            events,
            io,
            inputSource,
            submitHandler,
            slashPickerSupplier,
            NOOP_DIFF_VIEW_PROVIDER,
            null,
            width,
            height
        );
    }

    static JLineTuiTransport open(
        SessionRuntimeState state,
        EventBus events,
        TerminalIo io,
        TerminalInputSource inputSource,
        TuiSubmitHandler submitHandler,
        DiffViewProvider diffViewProvider,
        int width,
        int height
    ) throws IOException {
        return open(state, events, io, inputSource, submitHandler, null, diffViewProvider, null, width, height);
    }

    static JLineTuiTransport open(
        SessionRuntimeState state,
        EventBus events,
        TerminalIo io,
        TerminalInputSource inputSource,
        TuiSubmitHandler submitHandler,
        Supplier<SlashCommandPicker> slashPickerSupplier,
        DiffViewProvider diffViewProvider,
        ResumeSessionController resumeController,
        int width,
        int height
    ) throws IOException {
        return open(state, events, io, inputSource, submitHandler, slashPickerSupplier, diffViewProvider, resumeController, null, width, height);
    }

    static JLineTuiTransport open(
        SessionRuntimeState state,
        EventBus events,
        TerminalIo io,
        TerminalInputSource inputSource,
        TuiSubmitHandler submitHandler,
        Supplier<SlashCommandPicker> slashPickerSupplier,
        DiffViewProvider diffViewProvider,
        ResumeSessionController resumeController,
        Supplier<SkillIndex> skillIndexSupplier,
        int width,
        int height
    ) throws IOException {
        JLineTuiTransport[] holder = new JLineTuiTransport[1];
        TerminalSession session = TerminalSession.open(io, () -> {
            if (holder[0] != null) {
                holder[0].resize(io.width(), io.height());
            }
        }, () -> {
            if (holder[0] != null) {
                holder[0].handleInterruptSignal();
            }
        });
        TerminalFrameRenderer frameRenderer = TerminalFrameRenderer.withStartupPadding(io, session::updateRenderedRows);
        FrameSink frameSink = new FrameSink() {
            @Override
            public void render(List<String> lines) {
                render(TuiRenderFrame.transcriptOnly(lines));
            }

            @Override
            public void render(TuiRenderFrame frame) {
                try {
                    frameRenderer.render(frame);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            }
        };
        JLineTuiTransport transport = new JLineTuiTransport(
            frameSink,
            width,
            height,
            state,
            inputSource,
            submitHandler,
            session,
            slashPickerSupplier,
            diffViewProvider,
            resumeController,
            skillIndexSupplier
        );
        holder[0] = transport;
        try {
            transport.attach(events, state);
            transport.renderCurrentFrameUnderUiLock();
            return transport;
        } catch (RuntimeException exception) {
            closeAfterOpenFailure(transport, exception);
            throw exception;
        }
    }

    static JLineTuiTransport open(
        SessionRuntimeState state,
        EventBus events,
        TerminalIo io,
        TerminalInputSource inputSource,
        TuiSubmitHandler submitHandler,
        int width,
        int height
    ) throws IOException {
        return open(
            state,
            events,
            io,
            inputSource,
            submitHandler,
            null,
            NOOP_DIFF_VIEW_PROVIDER,
            null,
            width,
            height
        );
    }

    @Override
    public String name() {
        return "tui";
    }

    /**
     * 运行终端输入循环，直到用户请求退出。
     */
    public void runUntilExit() throws IOException {
        while (!exitRequested() && !Thread.currentThread().isInterrupted()) {
            drainInput();
            if (!exitRequested()) {
                sleepAfterEmptyPoll();
            }
        }
    }

    @Override
    public void attach(EventBus events, SessionRuntimeState state) {
        synchronized (uiMonitor) {
            closeSubscription();
            attachedEvents = events;
            runtimeState = state;
            if (reducer != null) {
                reducer.configureRuntimeState(state);
                syncInputLoopToolState(reducer.view());
            }
            subscription = events.subscribe(
                new EventFilter(Optional.ofNullable(state).map(SessionRuntimeState::sessionId), Optional.empty()),
                envelope -> {
                    if (reducer != null) {
                        reduceAndRenderUnderUiLock(envelope.event());
                    } else {
                        renderUnderUiLock();
                    }
                }
            );
        }
    }

    private void resumeRuntimeState(SessionRuntimeState state) {
        if (attachedEvents != null) {
            attach(attachedEvents, state);
        }
    }

    void reduceAndRenderUnderUiLock(AgentEvent event) {
        synchronized (uiMonitor) {
            uiLockEntries++;
            reducer.reduce(event);
            refreshDiffAfterToolEnd(event);
            renderCurrentFrame();
        }
    }

    void renderCurrentFrameUnderUiLock() {
        synchronized (uiMonitor) {
            uiLockEntries++;
            renderCurrentFrame();
        }
    }

    void renderUnderUiLock() {
        synchronized (uiMonitor) {
            uiLockEntries++;
            lastRenderHeldUiLock = Thread.holdsLock(uiMonitor);
            if (renderer != null) {
                renderer.run();
            }
        }
    }

    void runInputMutationForTest(Runnable mutation) {
        runUiMutation(mutation);
    }

    void runResizeMutationForTest(Runnable mutation) {
        runUiMutation(mutation);
    }

    void resizeForTest(int width, int height) {
        resize(width, height);
    }

    void drainInputForTest() throws IOException {
        drainInput();
    }

    int uiLockEntryCountForTest() {
        return uiLockEntries;
    }

    boolean isUiLockedForTest() {
        return lastRenderHeldUiLock;
    }

    boolean exitRequestedForTest() {
        return exitRequested();
    }

    int currentDraftLengthForTest() {
        return currentDraft().length();
    }

    @Override
    public void close() throws Exception {
        synchronized (uiMonitor) {
            closeSubscription();
            if (terminalSession != null) {
                terminalSession.close();
            }
        }
    }

    private void closeSubscription() {
        if (subscription == null) {
            return;
        }
        try {
            subscription.close();
        } catch (Exception ignored) {
            // NOTE: transport 关闭时订阅清理失败不应破坏终端恢复流程。
        } finally {
            subscription = null;
        }
    }

    private static void closeAfterOpenFailure(JLineTuiTransport transport, RuntimeException original) {
        try {
            transport.close();
        } catch (Exception closeFailure) {
            original.addSuppressed(closeFailure);
        }
    }

    private void runUiMutation(Runnable mutation) {
        synchronized (uiMonitor) {
            uiLockEntries++;
            mutation.run();
        }
    }

    private void drainInput() throws IOException {
        if (inputPump == null) {
            return;
        }
        for (int drained = 0; drained < MAX_INPUT_CHUNKS_PER_DRAIN; drained++) {
            Optional<String> chunk = inputPump.readChunk();
            if (chunk.isEmpty()) {
                if (inputPump.hasBufferedIncompleteKeySequence()) {
                    synchronized (uiMonitor) {
                        uiLockEntries++;
                        inputPump.flushBufferedInput();
                    }
                }
                return;
            }
            synchronized (uiMonitor) {
                uiLockEntries++;
                inputPump.dispatchChunk(chunk.orElseThrow());
            }
        }
    }

    private void resize(int width, int height) {
        synchronized (uiMonitor) {
            uiLockEntries++;
            if (reducer == null) {
                return;
            }
            int safeWidth = safeWidth(width);
            int safeHeight = safeHeight(height);
            screen = new TuiScreen(Math.max(1, safeHeight - 2));
            layout = new TuiLayout(safeWidth, safeHeight);
            if (inputLoop != null) {
                inputLoop.updateViewport(screen, layout);
            }
            renderCurrentFrame();
        }
    }

    private void handleInterruptSignal() {
        synchronized (uiMonitor) {
            uiLockEntries++;
            if (inputLoop != null) {
                inputLoop.acceptKey(TerminalKey.CTRL_C);
            }
        }
    }

    private void renderCurrentFrame() {
        TuiViewModel view = reducer.view();
        syncInputLoopToolState(view);
        frameSink.render(tuiRenderer.renderFrame(view, screen, layout, currentDraft(), currentCursor()));
    }

    private void refreshDiffAfterToolEnd(AgentEvent event) {
        if (!(event instanceof ToolEndEvent) || reducer == null) {
            return;
        }
        if (runtimeState == null || runtimeState.cwd() == null) {
            clearDiff();
            return;
        }
        diffViewProvider.currentDiff(runtimeState.cwd(), MAX_DIFF_PATCH_BYTES)
            .ifPresentOrElse(this::showDiffIfChanged, this::clearDiff);
    }

    private void showDiffIfChanged(DiffView diffView) {
        String snapshotHash = snapshotHash(diffView);
        if (snapshotHash.isBlank() || !snapshotHash.equals(lastDiffSnapshotHash)) {
            reducer.showDiff(diffView);
            lastDiffSnapshotHash = snapshotHash;
        }
    }

    private void clearDiff() {
        reducer.clearDiff();
        lastDiffSnapshotHash = "";
    }

    private String snapshotHash(DiffView diffView) {
        Object value = diffView.metadata().get("snapshotHash");
        return value == null ? "" : value.toString();
    }

    private String currentDraft() {
        return inputLoop == null ? "" : inputLoop.draft();
    }

    private int currentCursor() {
        return inputLoop == null ? -1 : inputLoop.cursor();
    }

    private void syncInputLoopToolState(TuiViewModel view) {
        if (inputLoop == null) {
            return;
        }
        boolean activeToolBlock = view.blocks()
            .stream()
            .anyMatch(block -> block instanceof TuiToolBlock tool && tool.active());
        boolean activeTurn = view.runtimeLine() != null && view.runtimeLine().startsWith("turn running ");
        inputLoop.setInterruptibleRunning(activeTurn || activeToolBlock || view.statusBar().hasInterruptibleTool());
    }

    private boolean exitRequested() {
        return inputLoop != null && inputLoop.exitRequested();
    }

    private void sleepAfterEmptyPoll() {
        try {
            Thread.sleep(10L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static int safeWidth(int width) {
        return width > 0 ? width : DEFAULT_TERMINAL_WIDTH;
    }

    private static int safeHeight(int height) {
        return height > 1 ? height : DEFAULT_TERMINAL_HEIGHT;
    }
}
