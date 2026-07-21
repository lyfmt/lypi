package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.event.ToolEndEvent;
import cn.lypi.contracts.event.ToolProgressEvent;
import cn.lypi.contracts.event.ToolStartEvent;
import cn.lypi.contracts.tui.TuiToolBlock;
import cn.lypi.contracts.tui.TuiToolState;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TuiToolProgressPerformanceTest {
    private static final int CHUNK_SIZE = 4 * 1024;
    private static final int ONE_MIB = 1024 * 1024;
    private static final long DEFAULT_MAX_FOUR_MIB_MILLIS = 1_500L;
    private static final Instant NOW = Instant.parse("2026-07-10T09:00:00Z");

    @Test
    void largeToolOutputRemainsBoundedAndScalesLinearly() {
        runWorkload(ONE_MIB);
        runWorkload(2 * ONE_MIB);
        runWorkload(4 * ONE_MIB);

        TimedWorkload oneMiB = measure(ONE_MIB);
        TimedWorkload twoMiB = measure(2 * ONE_MIB);
        TimedWorkload fourMiB = measure(4 * ONE_MIB);

        assertBounded(oneMiB, 255);
        assertBounded(twoMiB, 511);
        assertBounded(fourMiB, 1023);

        long maxFourMiBMillis = Long.getLong(
            "lypi.tui.progress.max4MiBMillis",
            DEFAULT_MAX_FOUR_MIB_MILLIS
        );
        assertTrue(
            fourMiB.duration().compareTo(Duration.ofMillis(maxFourMiBMillis)) < 0,
            () -> "4 MiB took " + formatMillis(fourMiB.duration()) + " ms"
        );
        assertTrue(
            fourMiB.duration().toNanos() <= twoMiB.duration().toNanos() * 3L,
            () -> "2 MiB took " + formatMillis(twoMiB.duration())
                + " ms, 4 MiB took " + formatMillis(fourMiB.duration()) + " ms"
        );

        System.out.printf(
            Locale.ROOT,
            "TUI tool progress: 1 MiB=%s ms, 2 MiB=%s ms, 4 MiB=%s ms%n",
            formatMillis(oneMiB.duration()),
            formatMillis(twoMiB.duration()),
            formatMillis(fourMiB.duration())
        );
    }

    private static TimedWorkload measure(int outputBytes) {
        long startedAt = System.nanoTime();
        WorkloadResult result = runWorkload(outputBytes);
        return new TimedWorkload(Duration.ofNanos(System.nanoTime() - startedAt), result);
    }

    private static WorkloadResult runWorkload(int outputBytes) {
        AtomicLong now = new AtomicLong();
        AtomicInteger renders = new AtomicInteger();
        JLineTuiTransport transport = JLineTuiTransport.withRenderer(
            ignored -> renders.incrementAndGet(),
            80,
            8,
            now::get,
            TuiRedrawScheduler.DEFAULT_FRAME_INTERVAL_NANOS
        );
        transport.reduceAndRequestRenderUnderUiLock(new ToolStartEvent("ses_1", "toolu_1", "bash", NOW));
        int chunks = outputBytes / CHUNK_SIZE;
        for (int index = 0; index < chunks; index++) {
            transport.reduceAndRequestRenderUnderUiLock(new ToolProgressEvent(
                "ses_1",
                "toolu_1",
                ToolProgress.output("stdout", chunk(index)),
                NOW
            ));
        }
        transport.reduceAndRequestRenderUnderUiLock(new ToolEndEvent("ses_1", "toolu_1", false, NOW));
        transport.flushPendingFrameForTest();

        TuiToolBlock tool = (TuiToolBlock) transport.viewForTest().blocks().getFirst();
        return new WorkloadResult(tool, renders.get());
    }

    private static String chunk(int index) {
        String label = "chunk-%04d ".formatted(index);
        return label + "x".repeat(CHUNK_SIZE - label.length() - 1) + "\n";
    }

    private static void assertBounded(TimedWorkload workload, int lastChunk) {
        TuiToolBlock tool = workload.result().tool();
        assertEquals(TuiToolState.DONE, tool.state());
        assertFalse(tool.active());
        assertTrue(tool.details().length() <= 17 * 1024);
        assertTrue(tool.details().contains("earlier output omitted"));
        assertTrue(tool.details().contains("chunk-%04d".formatted(lastChunk)));
        assertTrue(tool.details().contains("status succeeded"));
        assertTrue(workload.result().renderCount() <= 1);
    }

    private static String formatMillis(Duration duration) {
        return String.format(Locale.ROOT, "%.3f", duration.toNanos() / 1_000_000.0);
    }

    private record WorkloadResult(TuiToolBlock tool, int renderCount) {
    }

    private record TimedWorkload(Duration duration, WorkloadResult result) {
    }
}
