package cn.lypi.transport.tui;

import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.EventFilter;
import cn.lypi.contracts.event.EventSubscription;
import cn.lypi.contracts.tui.SessionRuntimeState;
import java.io.IOException;
import java.util.Optional;

public final class JLineTuiTransport implements TuiTransport, AutoCloseable {
    private final Object uiMonitor = new Object();
    private final Runnable renderer;
    private final TuiEventReducer reducer;
    private final TuiRenderer tuiRenderer;
    private TuiScreen screen;
    private TuiLayout layout;
    private final FrameSink frameSink;
    private final TerminalInputPump inputPump;
    private final TuiInputLoop inputLoop;
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
    }

    private JLineTuiTransport(FrameSink frameSink, int width, int height) {
        this.renderer = null;
        this.reducer = new TuiEventReducer();
        this.tuiRenderer = new TuiRenderer();
        this.screen = new TuiScreen(Math.max(1, height - 2));
        this.layout = new TuiLayout(width, height);
        this.frameSink = frameSink;
        this.inputPump = null;
        this.inputLoop = null;
    }

    private JLineTuiTransport(FrameSink frameSink, int width, int height, TerminalInputSource inputSource, TuiSubmitHandler submitHandler) {
        this.renderer = null;
        this.reducer = new TuiEventReducer();
        this.tuiRenderer = new TuiRenderer();
        this.screen = new TuiScreen(Math.max(1, height - 2));
        this.layout = new TuiLayout(width, height);
        this.frameSink = frameSink;
        this.inputLoop = new TuiInputLoop(submitHandler, frameSink, tuiRenderer, screen, layout, reducer::view);
        this.inputPump = new TerminalInputPump(inputSource, new KeyMapper(), inputLoop);
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
        return new JLineTuiTransport(frameSink, width, height, inputSource, submitHandler);
    }

    @Override
    public String name() {
        return "tui";
    }

    @Override
    public void attach(EventBus events, SessionRuntimeState state) {
        synchronized (uiMonitor) {
            closeSubscription();
            subscription = events.subscribe(
                new EventFilter(Optional.empty(), Optional.empty()),
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
            frameSink.render(tuiRenderer.render(reducer.view(), screen, layout, currentDraft()));
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

    @Override
    public void close() throws Exception {
        synchronized (uiMonitor) {
            closeSubscription();
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
                inputPump.drainAvailable();
            }
        }
    }

    private void resize(int width, int height) {
        synchronized (uiMonitor) {
            uiLockEntries++;
            if (reducer == null) {
                return;
            }
            screen = new TuiScreen(Math.max(1, height - 2));
            layout = new TuiLayout(width, height);
            if (inputLoop != null) {
                inputLoop.updateViewport(screen, layout);
            }
            frameSink.render(tuiRenderer.render(reducer.view(), screen, layout, currentDraft()));
        }
    }

    private String currentDraft() {
        return inputLoop == null ? "" : inputLoop.draft();
    }
}
