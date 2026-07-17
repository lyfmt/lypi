package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TuiRedrawSchedulerTest {
    @Test
    void firstRequestRendersImmediatelyWhenIdle() {
        AtomicLong now = new AtomicLong();
        AtomicInteger renders = new AtomicInteger();
        TuiRedrawScheduler scheduler = new TuiRedrawScheduler(now::get);

        scheduler.request();

        assertTrue(scheduler.renderIfDue(renders::incrementAndGet));
        assertEquals(1, renders.get());
        assertFalse(scheduler.pending());
    }

    @Test
    void coalescesRequestsWithinOneFrameWindow() {
        AtomicLong now = new AtomicLong();
        AtomicInteger renders = new AtomicInteger();
        TuiRedrawScheduler scheduler = new TuiRedrawScheduler(now::get);

        scheduler.request();
        assertTrue(scheduler.renderIfDue(renders::incrementAndGet));
        for (int index = 0; index < 100; index++) {
            scheduler.request();
            assertFalse(scheduler.renderIfDue(renders::incrementAndGet));
        }

        assertEquals(1, renders.get());
        assertTrue(scheduler.pending());
    }

    @Test
    void rendersLatestPendingStateOnceWhenFrameBecomesDue() {
        AtomicLong now = new AtomicLong();
        AtomicInteger renders = new AtomicInteger();
        TuiRedrawScheduler scheduler = new TuiRedrawScheduler(now::get);

        scheduler.request();
        scheduler.renderIfDue(renders::incrementAndGet);
        scheduler.request();
        scheduler.request();
        now.set(TuiRedrawScheduler.DEFAULT_FRAME_INTERVAL_NANOS);

        assertTrue(scheduler.renderIfDue(renders::incrementAndGet));
        assertEquals(2, renders.get());
        assertFalse(scheduler.renderIfDue(renders::incrementAndGet));
    }

    @Test
    void renderNowBypassesWindowAndClearsPendingRequest() {
        AtomicLong now = new AtomicLong();
        AtomicInteger renders = new AtomicInteger();
        TuiRedrawScheduler scheduler = new TuiRedrawScheduler(now::get);

        scheduler.request();
        scheduler.renderIfDue(renders::incrementAndGet);
        scheduler.request();

        scheduler.renderNow(renders::incrementAndGet);

        assertEquals(2, renders.get());
        assertFalse(scheduler.pending());
        assertFalse(scheduler.renderIfDue(renders::incrementAndGet));
    }

    @Test
    void failedRenderKeepsRequestPendingForImmediateRetry() {
        AtomicLong now = new AtomicLong();
        AtomicInteger renders = new AtomicInteger();
        TuiRedrawScheduler scheduler = new TuiRedrawScheduler(now::get);
        scheduler.request();

        assertThrows(IllegalStateException.class, () -> scheduler.renderIfDue(() -> {
            throw new IllegalStateException("render failed");
        }));

        assertTrue(scheduler.pending());
        assertTrue(scheduler.renderIfDue(renders::incrementAndGet));
        assertEquals(1, renders.get());
    }
}
