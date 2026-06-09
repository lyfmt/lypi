package cn.lypi.transport.tui;

import cn.lypi.contracts.common.AbortSignal;
import java.util.concurrent.atomic.AtomicBoolean;

final class MutableAbortSignal implements AbortSignal {
    private final AtomicBoolean aborted = new AtomicBoolean(false);

    @Override
    public boolean aborted() {
        return aborted.get();
    }

    void abort() {
        aborted.set(true);
    }
}
