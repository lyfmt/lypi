package cn.lypi.transport.tui;

import java.util.Objects;
import java.util.function.LongSupplier;

final class TuiRedrawScheduler {
    static final long DEFAULT_FRAME_INTERVAL_NANOS = 16_000_000L;

    private final LongSupplier nanoTime;
    private final long frameIntervalNanos;
    private boolean pending;
    private boolean rendered;
    private long lastRenderNanos;

    TuiRedrawScheduler() {
        this(System::nanoTime, DEFAULT_FRAME_INTERVAL_NANOS);
    }

    TuiRedrawScheduler(LongSupplier nanoTime) {
        this(nanoTime, DEFAULT_FRAME_INTERVAL_NANOS);
    }

    TuiRedrawScheduler(LongSupplier nanoTime, long frameIntervalNanos) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        if (frameIntervalNanos < 0L) {
            throw new IllegalArgumentException("frameIntervalNanos must not be negative");
        }
        this.frameIntervalNanos = frameIntervalNanos;
    }

    void request() {
        pending = true;
    }

    boolean renderIfDue(Runnable render) {
        if (!pending) {
            return false;
        }
        long now = nanoTime.getAsLong();
        if (rendered && !frameIsDue(now)) {
            return false;
        }
        runRender(render);
        return true;
    }

    void renderNow(Runnable render) {
        runRender(render);
    }

    boolean pending() {
        return pending;
    }

    private boolean frameIsDue(long now) {
        if (frameIntervalNanos == 0L || now < lastRenderNanos) {
            return true;
        }
        return now - lastRenderNanos >= frameIntervalNanos;
    }

    private void runRender(Runnable render) {
        Objects.requireNonNull(render, "render");
        pending = false;
        try {
            render.run();
            rendered = true;
            lastRenderNanos = nanoTime.getAsLong();
        } catch (RuntimeException | Error failure) {
            pending = true;
            throw failure;
        }
    }
}
