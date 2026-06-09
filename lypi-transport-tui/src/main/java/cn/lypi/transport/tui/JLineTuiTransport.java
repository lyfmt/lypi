package cn.lypi.transport.tui;

import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.EventFilter;
import cn.lypi.contracts.event.EventSubscription;
import cn.lypi.contracts.runtime.AgentCorePort;
import cn.lypi.contracts.tui.SessionRuntimeState;
import cn.lypi.contracts.tui.SlashCommand;
import cn.lypi.contracts.tui.TuiToolBlock;
import cn.lypi.contracts.tui.TuiViewModel;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import org.jline.terminal.Terminal;

public final class JLineTuiTransport implements TuiTransport, AutoCloseable {
    private static final int DEFAULT_TERMINAL_WIDTH = 80;
    private static final int DEFAULT_TERMINAL_HEIGHT = 24;
    private static final int MAX_INPUT_CHUNKS_PER_DRAIN = 32;

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
    private EventSubscription subscription;
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
    }

    private JLineTuiTransport(
        FrameSink frameSink,
        int width,
        int height,
        TerminalInputSource inputSource,
        TuiSubmitHandler submitHandler,
        TerminalSession terminalSession
    ) {
        this.renderer = null;
        this.reducer = new TuiEventReducer();
        this.tuiRenderer = new TuiRenderer();
        int safeWidth = safeWidth(width);
        int safeHeight = safeHeight(height);
        this.screen = new TuiScreen(Math.max(1, safeHeight - 2));
        this.layout = new TuiLayout(safeWidth, safeHeight);
        this.frameSink = frameSink;
        this.inputLoop = new TuiInputLoop(submitHandler, frameSink, tuiRenderer, screen, layout, reducer::view);
        this.inputPump = new TerminalInputPump(inputSource, new KeyMapper(), inputLoop);
        this.terminalSession = terminalSession;
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
        JLineTerminalIo io = new JLineTerminalIo(terminal);
        return open(
            state,
            core,
            events,
            io,
            new JLineTerminalInputSource(terminal),
            command -> Thread.ofVirtual().name("lypi-tui-turn-", 0).start(command),
            slashCommands,
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
        return open(
            state,
            events,
            io,
            inputSource,
            new RuntimeTuiSubmitHandler(state.sessionId(), core, events, executor, slashCommands),
            width,
            height
        );
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
        return new JLineTuiTransport(frameSink, width, height, inputSource, submitHandler, null);
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
        JLineTuiTransport[] holder = new JLineTuiTransport[1];
        TerminalSession session = TerminalSession.open(io, () -> {
            if (holder[0] != null) {
                holder[0].resize(io.width(), io.height());
            }
        });
        TerminalFrameRenderer frameRenderer = new TerminalFrameRenderer(io);
        FrameSink frameSink = lines -> {
            try {
                frameRenderer.render(lines);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        };
        JLineTuiTransport transport = new JLineTuiTransport(frameSink, width, height, inputSource, submitHandler, session);
        holder[0] = transport;
        transport.attach(events, state);
        return transport;
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

    void reduceAndRenderUnderUiLock(cn.lypi.contracts.event.AgentEvent event) {
        synchronized (uiMonitor) {
            uiLockEntries++;
            reducer.reduce(event);
            TuiViewModel view = reducer.view();
            syncInputLoopToolState(view);
            frameSink.render(tuiRenderer.render(view, screen, layout, currentDraft(), currentCursor()));
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

    private void runUiMutation(Runnable mutation) {
        synchronized (uiMonitor) {
            uiLockEntries++;
            mutation.run();
        }
    }

    private void drainInput() throws IOException {
        synchronized (uiMonitor) {
            uiLockEntries++;
            if (inputPump != null) {
                inputPump.drainAvailable(MAX_INPUT_CHUNKS_PER_DRAIN);
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
            frameSink.render(tuiRenderer.render(reducer.view(), screen, layout, currentDraft(), currentCursor()));
        }
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
        boolean toolRunning = view.blocks()
            .stream()
            .anyMatch(block -> block instanceof TuiToolBlock tool && tool.active());
        inputLoop.setToolRunning(toolRunning);
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
