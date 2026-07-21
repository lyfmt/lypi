package cn.lypi.transport.tui;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.common.SignalSubscription;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class MutableAbortSignal implements AbortSignal {
    private final Object listenerLock = new Object();
    private final List<Runnable> listeners = new ArrayList<>();
    private volatile boolean aborted;

    @Override
    public boolean aborted() {
        return aborted;
    }

    @Override
    public SignalSubscription subscribe(Runnable listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        boolean notifyImmediately;
        synchronized (listenerLock) {
            notifyImmediately = aborted;
            if (!notifyImmediately) {
                listeners.add(listener);
            }
        }
        if (notifyImmediately) {
            listener.run();
        }
        return () -> {
            synchronized (listenerLock) {
                listeners.remove(listener);
            }
        };
    }

    void abort() {
        notifyListeners(abortAndDrainListeners());
    }

    List<Runnable> abortAndDrainListeners() {
        List<Runnable> listenersToNotify;
        synchronized (listenerLock) {
            if (aborted) {
                return List.of();
            }
            aborted = true;
            listenersToNotify = List.copyOf(listeners);
            listeners.clear();
        }
        return listenersToNotify;
    }

    static void notifyListeners(List<Runnable> listenersToNotify) {
        for (Runnable listener : listenersToNotify) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
            }
        }
    }
}
