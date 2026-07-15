package cn.lypi.transport.tui;

import cn.lypi.contracts.context.ContentBlockKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.EventFilter;
import cn.lypi.contracts.event.EventSubscription;
import cn.lypi.contracts.event.MessageDeltaEvent;
import cn.lypi.contracts.runtime.AgentCorePort;
import cn.lypi.contracts.runtime.CompactionRuntimePort;
import cn.lypi.contracts.runtime.ResourceRuntimePort;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.skill.SkillIndex;
import cn.lypi.contracts.tui.DiffViewProvider;
import cn.lypi.contracts.tui.NewSessionController;
import cn.lypi.contracts.tui.ResumeSessionController;
import cn.lypi.contracts.tui.SessionRuntimeState;
import cn.lypi.contracts.tui.SlashCommand;
import cn.lypi.contracts.tui.TuiBlock;
import cn.lypi.contracts.tui.TuiToolBlock;
import cn.lypi.contracts.tui.TuiViewModel;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jline.terminal.Terminal;

public final class JLineTuiTransport implements TuiTransport, AutoCloseable {
    private static final int DEFAULT_TERMINAL_WIDTH = 80;
    private static final int DEFAULT_TERMINAL_HEIGHT = 24;
    private static final int MAX_INPUT_CHUNKS_PER_DRAIN = 32;
    private static final int MAX_DIFF_PATCH_BYTES = 64 * 1024;
    private static final long RUNTIME_TICK_INTERVAL_MILLIS = 1_000L;
    private static final Duration CURSOR_PROBE_TIMEOUT = Duration.ofMillis(100);
    private static final DiffViewProvider NOOP_DIFF_VIEW_PROVIDER = (cwd, maxPatchBytes) -> Optional.empty();

    private final Object uiMonitor = new Object();
    private final Runnable renderer;
    private final TuiEventReducer reducer;
    private final TuiRenderer tuiRenderer;
    private final TuiTranscriptPartitioner transcriptPartitioner;
    private final TuiTranscriptCommitLedger commitLedger;
    private TuiLayout layout;
    private final FrameSink frameSink;
    private final TerminalInputPump inputPump;
    private final TuiInputLoop inputLoop;
    private final TerminalSession terminalSession;
    private final TerminalIo terminalIo;
    private final InlineTerminalRenderer inlineTerminalRenderer;
    private final DiffViewProvider diffViewProvider;
    private final Clock clock;
    private final TuiRuntimeTicker runtimeTicker;
    private final TuiRedrawScheduler redrawScheduler;
    private SessionRuntimeState runtimeState;
    private EventSubscription subscription;
    private EventBus attachedEvents;
    private boolean lastRenderHeldUiLock;
    private volatile boolean terminalIoFailed;
    private int uiLockEntries;

    public JLineTuiTransport(Runnable renderer) {
        this.renderer = renderer;
        this.reducer = null;
        this.tuiRenderer = null;
        this.transcriptPartitioner = null;
        this.commitLedger = null;
        this.layout = null;
        this.frameSink = null;
        this.inputPump = null;
        this.inputLoop = null;
        this.terminalSession = null;
        this.terminalIo = null;
        this.inlineTerminalRenderer = null;
        this.diffViewProvider = NOOP_DIFF_VIEW_PROVIDER;
        this.clock = Clock.systemUTC();
        this.runtimeTicker = new TuiRuntimeTicker(RUNTIME_TICK_INTERVAL_MILLIS, MAX_DIFF_PATCH_BYTES);
        this.redrawScheduler = new TuiRedrawScheduler();
        this.runtimeState = null;
    }

    private JLineTuiTransport(FrameSink frameSink, int width, int height) {
        this(frameSink, width, height, null);
    }

    private JLineTuiTransport(FrameSink frameSink, int width, int height, TerminalSession terminalSession) {
        this(frameSink, width, height, terminalSession, new TuiRedrawScheduler());
    }

    private JLineTuiTransport(
        FrameSink frameSink,
        int width,
        int height,
        TerminalSession terminalSession,
        TuiRedrawScheduler redrawScheduler
    ) {
        this.renderer = null;
        this.reducer = new TuiEventReducer();
        this.tuiRenderer = new TuiRenderer();
        this.transcriptPartitioner = new TuiTranscriptPartitioner();
        this.commitLedger = new TuiTranscriptCommitLedger();
        int safeWidth = safeWidth(width);
        int safeHeight = safeHeight(height);
        this.layout = new TuiLayout(safeWidth, safeHeight);
        this.frameSink = frameSink;
        this.inputPump = null;
        this.inputLoop = null;
        this.terminalSession = terminalSession;
        this.terminalIo = null;
        this.inlineTerminalRenderer = null;
        this.diffViewProvider = NOOP_DIFF_VIEW_PROVIDER;
        this.clock = Clock.systemUTC();
        this.runtimeTicker = new TuiRuntimeTicker(RUNTIME_TICK_INTERVAL_MILLIS, MAX_DIFF_PATCH_BYTES);
        this.redrawScheduler = redrawScheduler;
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
        TerminalIo terminalIo,
        InlineTerminalRenderer inlineTerminalRenderer,
        Supplier<SlashCommandPicker> slashPickerSupplier,
        DiffViewProvider diffViewProvider,
        ResumeSessionController resumeController,
        Supplier<SkillIndex> skillIndexSupplier
    ) {
        this(
            frameSink,
            width,
            height,
            state,
            inputSource,
            submitHandler,
            terminalSession,
            terminalIo,
            inlineTerminalRenderer,
            slashPickerSupplier,
            diffViewProvider,
            resumeController,
            skillIndexSupplier,
            Clock.systemUTC()
        );
    }

    private JLineTuiTransport(
        FrameSink frameSink,
        int width,
        int height,
        SessionRuntimeState state,
        TerminalInputSource inputSource,
        TuiSubmitHandler submitHandler,
        TerminalSession terminalSession,
        TerminalIo terminalIo,
        InlineTerminalRenderer inlineTerminalRenderer,
        Supplier<SlashCommandPicker> slashPickerSupplier,
        DiffViewProvider diffViewProvider,
        ResumeSessionController resumeController,
        Supplier<SkillIndex> skillIndexSupplier,
        Clock clock
    ) {
        this.renderer = null;
        this.redrawScheduler = new TuiRedrawScheduler();
        this.reducer = TuiEventReducer.fromRuntimeState(state);
        this.tuiRenderer = new TuiRenderer();
        this.transcriptPartitioner = new TuiTranscriptPartitioner();
        this.commitLedger = new TuiTranscriptCommitLedger();
        int safeWidth = safeWidth(width);
        int safeHeight = safeHeight(height);
        this.layout = new TuiLayout(safeWidth, safeHeight);
        this.frameSink = frameSink;
        this.inputLoop = new TuiInputLoop(
            submitHandler,
            this::renderImmediateFrame,
            layout,
            reducer::view,
            slashPickerSupplier,
            resumeController,
            this::resumeRuntimeState,
            skillIndexSupplier
        );
        this.inputPump = new TerminalInputPump(inputSource, new KeyMapper(), inputLoop);
        this.terminalSession = terminalSession;
        this.terminalIo = terminalIo;
        this.inlineTerminalRenderer = inlineTerminalRenderer;
        this.diffViewProvider = diffViewProvider == null ? NOOP_DIFF_VIEW_PROVIDER : diffViewProvider;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.runtimeTicker = new TuiRuntimeTicker(RUNTIME_TICK_INTERVAL_MILLIS, MAX_DIFF_PATCH_BYTES);
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
        RuntimeTuiSubmitHandler submitHandler = new RuntimeTuiSubmitHandler(
            state.sessionId(),
            core,
            events,
            command -> Thread.ofVirtual().name("lypi-tui-turn-", 0).start(command),
            slashCommands
        );
        return openTerminal(
            state,
            events,
            terminal,
            submitHandler,
            null,
            diffViewProvider,
            resumeController,
            null
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
        JLineTuiTransport transport = openTerminal(
            state,
            events,
            terminal,
            submitHandler,
            () -> new SlashCommandPicker(router.commandNames()),
            diffViewProvider,
            resumeController,
            () -> resourceRuntime.load(state.cwd()).skillIndex()
        );
        holder[0] = transport;
        return transport;
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

    static JLineTuiTransport withBatchRenderer(FrameSink frameSink, int width, int height) {
        return new JLineTuiTransport(frameSink, width, height);
    }

    static JLineTuiTransport withRenderer(
        Consumer<List<String>> frameConsumer,
        int width,
        int height
    ) {
        return withBatchRenderer(legacyFrameSink(frameConsumer), width, height);
    }

    static JLineTuiTransport withRenderer(
        Consumer<List<String>> frameConsumer,
        int width,
        int height,
        LongSupplier nanoTime,
        long frameIntervalNanos
    ) {
        return new JLineTuiTransport(
            legacyFrameSink(frameConsumer),
            width,
            height,
            null,
            new TuiRedrawScheduler(nanoTime, frameIntervalNanos)
        );
    }

    static JLineTuiTransport withInput(
        Consumer<List<String>> frameConsumer,
        int width,
        int height,
        TerminalInputSource inputSource,
        TuiSubmitHandler submitHandler
    ) {
        return withBatchInput(
            legacyFrameSink(frameConsumer),
            width,
            height,
            inputSource,
            submitHandler
        );
    }

    static JLineTuiTransport withBatchInput(
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
            null,
            null,
            NOOP_DIFF_VIEW_PROVIDER,
            null,
            null
        );
    }

    static JLineTuiTransport withInput(
        Consumer<List<String>> frameConsumer,
        int width,
        int height,
        TerminalInputSource inputSource,
        TuiSubmitHandler submitHandler,
        Clock clock
    ) {
        return withBatchInput(
            legacyFrameSink(frameConsumer),
            width,
            height,
            inputSource,
            submitHandler,
            clock
        );
    }

    static JLineTuiTransport withBatchInput(
        FrameSink frameSink,
        int width,
        int height,
        TerminalInputSource inputSource,
        TuiSubmitHandler submitHandler,
        Clock clock
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
            null,
            null,
            NOOP_DIFF_VIEW_PROVIDER,
            null,
            null,
            clock
        );
    }

    private static FrameSink legacyFrameSink(Consumer<List<String>> frameConsumer) {
        return batch -> {
            List<String> lines = new java.util.ArrayList<>(
                batch.historyLines().size() + batch.surface().lines().size()
            );
            batch.historyLines().stream().map(TerminalLine::text).forEach(lines::add);
            lines.addAll(batch.surface().lines());
            frameConsumer.accept(List.copyOf(lines));
        };
    }

    private static JLineTuiTransport openTerminal(
        SessionRuntimeState state,
        EventBus events,
        Terminal terminal,
        TuiSubmitHandler submitHandler,
        Supplier<SlashCommandPicker> slashPickerSupplier,
        DiffViewProvider diffViewProvider,
        ResumeSessionController resumeController,
        Supplier<SkillIndex> skillIndexSupplier
    ) throws IOException {
        JLineTerminalIo io = new JLineTerminalIo(terminal);
        JLineTuiTransport[] holder = new JLineTuiTransport[1];
        TerminalSession session = null;
        InlineTerminalRenderer terminalRenderer = null;
        JLineTuiTransport transport = null;
        try {
            session = TerminalSession.open(io, () -> {
                if (holder[0] != null) {
                    holder[0].resize(io.width(), io.height());
                }
            }, () -> {
                if (holder[0] != null) {
                    holder[0].handleInterruptSignal();
                }
            });
            CursorProbeResult probe = TerminalCursorProbe.query(terminal, CURSOR_PROBE_TIMEOUT);
            int initialWidth = safeWidth(io.width());
            int initialHeight = safeHeight(io.height());
            InlineViewport viewport;
            if (probe.position().isPresent()) {
                viewport = InlineViewport.at(probe.position().orElseThrow(), initialWidth, initialHeight);
            } else {
                io.write("\r\n");
                io.flush();
                viewport = initialViewport(initialWidth, initialHeight);
            }
            TerminalInputSource inputSource = new JLineTerminalInputSource(terminal, probe.replayInput());
            InlineTerminalRenderer nextRenderer = new InlineTerminalRenderer(io, viewport);
            terminalRenderer = nextRenderer;
            transport = new JLineTuiTransport(
                terminalFrameSink(nextRenderer),
                initialWidth,
                initialHeight,
                state,
                inputSource,
                submitHandler,
                session,
                io,
                nextRenderer,
                slashPickerSupplier,
                diffViewProvider,
                resumeController,
                skillIndexSupplier
            );
            holder[0] = transport;
            transport.attach(events, state);
            transport.renderCurrentFrameUnderUiLock();
            return transport;
        } catch (IOException | RuntimeException exception) {
            if (transport != null) {
                closeAfterOpenFailure(transport, exception);
            } else {
                closeAfterOpenFailure(terminalRenderer, session, exception);
            }
            throw exception;
        }
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
        InlineTerminalRenderer terminalRenderer = null;
        JLineTuiTransport transport = null;
        try {
            int initialWidth = safeWidth(width > 0 ? width : io.width());
            int initialHeight = safeHeight(height > 1 ? height : io.height());
            InlineTerminalRenderer nextRenderer = new InlineTerminalRenderer(
                io,
                initialViewport(initialWidth, initialHeight)
            );
            terminalRenderer = nextRenderer;
            transport = new JLineTuiTransport(
                terminalFrameSink(nextRenderer),
                initialWidth,
                initialHeight,
                state,
                inputSource,
                submitHandler,
                session,
                io,
                nextRenderer,
                slashPickerSupplier,
                diffViewProvider,
                resumeController,
                skillIndexSupplier
            );
            holder[0] = transport;
            transport.attach(events, state);
            transport.renderCurrentFrameUnderUiLock();
            return transport;
        } catch (RuntimeException exception) {
            if (transport != null) {
                closeAfterOpenFailure(transport, exception);
            } else {
                closeAfterOpenFailure(terminalRenderer, session, exception);
            }
            throw exception;
        }
    }

    private static FrameSink terminalFrameSink(InlineTerminalRenderer terminalRenderer) {
        return batch -> {
            try {
                terminalRenderer.render(batch);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        };
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
        try {
            while (!exitRequested() && !Thread.currentThread().isInterrupted()) {
                drainInput();
                if (!exitRequested()) {
                    renderRuntimeTickIfDue();
                    renderPendingFrameIfDue();
                    sleepAfterEmptyPoll();
                }
            }
        } catch (IOException exception) {
            terminalIoFailed = true;
            throw exception;
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
                        reduceAndRequestRenderUnderUiLock(envelope.event());
                    } else {
                        requestRenderUnderUiLock();
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

    void reduceAndRequestRenderUnderUiLock(AgentEvent event) {
        synchronized (uiMonitor) {
            uiLockEntries++;
            reducer.reduce(event);
            runtimeTicker.refreshDiffAfterToolEnd(event, runtimeState, reducer, diffViewProvider);
            syncInputLoopToolState(reducer.view());
            redrawScheduler.request();
            if (visibleStreamingDelta(event)) {
                redrawScheduler.renderIfDue(this::renderCurrentFrame);
            }
        }
    }

    private static boolean visibleStreamingDelta(AgentEvent event) {
        if (!(event instanceof MessageDeltaEvent delta)
            || delta.role() != MessageRole.ASSISTANT
            || delta.delta().isEmpty()) {
            return false;
        }
        return delta.blockKind() == ContentBlockKind.TEXT
            || delta.blockKind() == ContentBlockKind.THINKING;
    }

    private void requestRenderUnderUiLock() {
        synchronized (uiMonitor) {
            uiLockEntries++;
            redrawScheduler.request();
        }
    }

    void renderCurrentFrameUnderUiLock() {
        synchronized (uiMonitor) {
            uiLockEntries++;
            redrawScheduler.renderNow(this::renderCurrentFrame);
        }
    }

    void renderUnderUiLock() {
        synchronized (uiMonitor) {
            uiLockEntries++;
            redrawScheduler.renderNow(this::renderCurrentFrame);
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

    void renderRuntimeTickForTest() {
        renderRuntimeTickUnderUiLock();
    }

    boolean renderPendingFrameIfDueForTest() {
        return renderPendingFrameIfDue();
    }

    void flushPendingFrameForTest() {
        synchronized (uiMonitor) {
            if (!redrawScheduler.pending()) {
                return;
            }
            uiLockEntries++;
            redrawScheduler.renderNow(this::renderCurrentFrame);
        }
    }

    TuiViewModel viewForTest() {
        synchronized (uiMonitor) {
            return reducer.view();
        }
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
        Exception failure = null;
        synchronized (uiMonitor) {
            closeSubscription();
            if (!terminalIoFailed && redrawScheduler.pending() && reducer != null) {
                try {
                    redrawScheduler.renderNow(this::renderCurrentFrame);
                } catch (RuntimeException exception) {
                    failure = exception;
                }
            }
            if (inlineTerminalRenderer != null) {
                try {
                    inlineTerminalRenderer.finish();
                } catch (Exception exception) {
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            }
            if (terminalSession != null) {
                try {
                    terminalSession.close();
                } catch (Exception exception) {
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            }
        }
        if (failure != null) {
            throw failure;
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

    private static void closeAfterOpenFailure(JLineTuiTransport transport, Throwable original) {
        try {
            transport.close();
        } catch (Exception closeFailure) {
            original.addSuppressed(closeFailure);
        }
    }

    private static void closeAfterOpenFailure(
        InlineTerminalRenderer terminalRenderer,
        TerminalSession session,
        Throwable original
    ) {
        if (terminalRenderer != null) {
            try {
                terminalRenderer.finish();
            } catch (Exception closeFailure) {
                original.addSuppressed(closeFailure);
            }
        }
        if (session != null) {
            try {
                session.close();
            } catch (Exception closeFailure) {
                original.addSuppressed(closeFailure);
            }
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
                        flushPendingFrameNow();
                        inputPump.flushBufferedInput();
                    }
                }
                return;
            }
            synchronized (uiMonitor) {
                uiLockEntries++;
                flushPendingFrameNow();
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
            updateViewport(width, height);
            redrawScheduler.renderNow(this::renderCurrentFrame);
        }
    }

    private void updateViewport(int width, int height) {
        int safeWidth = safeWidth(width);
        int safeHeight = safeHeight(height);
        layout = new TuiLayout(safeWidth, safeHeight);
        if (inlineTerminalRenderer != null) {
            inlineTerminalRenderer.resize(safeWidth, safeHeight);
        }
        if (inputLoop != null) {
            inputLoop.updateLayout(layout);
        }
    }

    private void reconcileTerminalSize() {
        if (terminalIo == null || layout == null) {
            return;
        }
        int currentWidth = terminalIo.width();
        int currentHeight = terminalIo.height();
        int resolvedWidth = currentWidth > 0 ? currentWidth : layout.width();
        int resolvedHeight = currentHeight > 1 ? currentHeight : layout.height();
        if (layout.width() != resolvedWidth || layout.height() != resolvedHeight) {
            updateViewport(resolvedWidth, resolvedHeight);
        }
    }

    private void handleInterruptSignal() {
        synchronized (uiMonitor) {
            uiLockEntries++;
            if (inputLoop != null) {
                flushPendingFrameNow();
                inputLoop.acceptKey(TerminalKey.CTRL_C);
            }
        }
    }

    private void renderCurrentFrame() {
        lastRenderHeldUiLock = Thread.holdsLock(uiMonitor);
        try {
            reconcileTerminalSize();
            if (renderer != null) {
                renderer.run();
                return;
            }
            if (reducer == null) {
                return;
            }
            reducer.observeRuntimeAt(clock.instant());
            TuiViewModel view = reducer.view();
            syncInputLoopToolState(view);
            TuiViewModel renderView = inputLoop == null ? view : inputLoop.viewForRender();
            TuiTranscriptPartition partition = transcriptPartitioner.partition(renderView.blocks());
            List<TuiBlock> newlyCommitted = commitLedger.advance(projectionKey(), partition.history());
            List<TerminalLine> historyLines = tuiRenderer.renderCommittedBlocks(newlyCommitted, layout.width());
            TuiRenderFrame surface = tuiRenderer.renderSurface(
                renderView,
                partition.live(),
                layout,
                currentDraft(),
                currentCursor(),
                inputLoop == null ? List.of() : inputLoop.overlayLines(),
                inputLoop != null && inputLoop.toolOutputExpanded()
            );
            frameSink.render(new TuiRenderBatch(historyLines, surface));
        } catch (UncheckedIOException exception) {
            terminalIoFailed = true;
            throw exception;
        }
    }

    private void renderImmediateFrame() {
        redrawScheduler.renderNow(this::renderCurrentFrame);
    }

    private void flushPendingFrameNow() {
        if (redrawScheduler.pending()) {
            redrawScheduler.renderNow(this::renderCurrentFrame);
        }
    }

    private boolean renderPendingFrameIfDue() {
        synchronized (uiMonitor) {
            if (!redrawScheduler.pending()) {
                return false;
            }
            uiLockEntries++;
            return redrawScheduler.renderIfDue(this::renderCurrentFrame);
        }
    }

    private void renderRuntimeTickIfDue() {
        runtimeTicker.renderTickIfDue(
            reducer != null && reducer.hasActiveTurn(),
            clock.instant(),
            this::renderRuntimeTickUnderUiLock
        );
    }

    private void renderRuntimeTickUnderUiLock() {
        synchronized (uiMonitor) {
            if (reducer == null || !reducer.hasActiveTurn()) {
                return;
            }
            uiLockEntries++;
            redrawScheduler.renderNow(this::renderCurrentFrame);
        }
    }

    private String currentDraft() {
        return inputLoop == null ? "" : inputLoop.draft();
    }

    private int currentCursor() {
        return inputLoop == null ? -1 : inputLoop.cursor();
    }

    private TuiProjectionKey projectionKey() {
        if (runtimeState == null) {
            return new TuiProjectionKey("", "");
        }
        return new TuiProjectionKey(runtimeState.sessionId(), runtimeState.currentBranchLeafId());
    }

    private void syncInputLoopToolState(TuiViewModel view) {
        if (inputLoop == null) {
            return;
        }
        boolean activeToolBlock = view.blocks()
            .stream()
            .anyMatch(block -> block instanceof TuiToolBlock tool && tool.active());
        boolean activeTurn = view.runtimeLine() != null && view.runtimeLine().startsWith("working (");
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

    private static InlineViewport initialViewport(int width, int height) {
        return new InlineViewport(Math.max(0, height - 1), 1, width, height);
    }
}
