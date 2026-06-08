package cn.lypi.transport.tui;

import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.EventFilter;
import cn.lypi.contracts.event.EventSubscription;
import cn.lypi.contracts.tui.SessionRuntimeState;
import java.util.Optional;

public final class JLineTuiTransport implements TuiTransport, AutoCloseable {
    private final Object uiMonitor = new Object();
    private final Runnable renderer;
    private EventSubscription subscription;
    private boolean lastRenderHeldUiLock;

    public JLineTuiTransport(Runnable renderer) {
        this.renderer = renderer;
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
                envelope -> renderUnderUiLock()
            );
        }
    }

    void renderUnderUiLock() {
        synchronized (uiMonitor) {
            lastRenderHeldUiLock = Thread.holdsLock(uiMonitor);
            renderer.run();
        }
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
}
