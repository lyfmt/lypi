package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.event.ToolEndEvent;
import cn.lypi.contracts.tool.ToolExecutionStatus;
import cn.lypi.contracts.tool.ToolResultSummary;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TuiToolProgressBufferTest {
    private static final Instant NOW = Instant.parse("2026-07-10T09:00:00Z");

    @Test
    void retainsOnlyTheBoundedOutputTail() {
        TuiToolProgressBuffer buffer = new TuiToolProgressBuffer();

        for (int index = 0; index < 300; index++) {
            String line = "line-%03d %s\n".formatted(index, "x".repeat(1014));
            buffer.append(ToolProgress.output("stdout", line));
        }

        assertTrue(buffer.retainedCharacters() <= 16 * 1024);
        assertTrue(buffer.retainedLineCount() <= 200);
        assertTrue(buffer.render().contains("line-299"));
        assertFalse(buffer.render().contains("line-000"));
        assertTrue(buffer.render().contains("earlier output omitted"));
    }

    @Test
    void preservesPhysicalLinesAcrossCrLfAndPartialChunks() {
        TuiToolProgressBuffer buffer = new TuiToolProgressBuffer();

        buffer.append(ToolProgress.output("stdout", "first\r"));
        buffer.append(ToolProgress.output("stdout", "\nsecond\r"));
        buffer.append(ToolProgress.output("stdout", "third"));

        assertEquals("stdout: first\nstdout: second\nstdout: third", buffer.render());
        assertEquals(3, buffer.retainedLineCount());
    }

    @Test
    void replacesStateProgressAndFreezesAfterCompletion() {
        TuiToolProgressBuffer buffer = new TuiToolProgressBuffer();

        for (int index = 0; index < 100; index++) {
            buffer.append(ToolProgress.phase("phase-" + index, "phase title " + index));
            buffer.append(ToolProgress.status("status", "detail-" + index));
            buffer.append(ToolProgress.counter("items", index, 100));
            buffer.append(ToolProgress.percent("percent", index));
        }
        buffer.append(ToolProgress.output("stdout", "latest output\n"));

        String running = buffer.render();
        assertTrue(running.contains("phase-99"));
        assertTrue(running.contains("status detail-99"));
        assertTrue(running.contains("items 99/100"));
        assertTrue(running.contains("percent 99%"));
        assertTrue(running.contains("stdout: latest output"));
        assertFalse(running.contains("phase-0\n"));
        assertFalse(running.contains("status detail-0\n"));

        buffer.complete(successfulEnd());

        assertFalse(buffer.active());
        assertTrue(buffer.render().contains("exit 0"));
        assertTrue(buffer.render().contains("complete"));
        String completed = buffer.render();
        buffer.append(ToolProgress.status("status", "too late"));
        assertEquals(completed, buffer.render());
    }

    private static ToolEndEvent successfulEnd() {
        return new ToolEndEvent(
            "ses_1",
            "toolu_1",
            ToolExecutionStatus.SUCCEEDED,
            0,
            new ToolResultSummary("bash succeeded", "complete", false, 0, false, 42L, Map.of()),
            null,
            NOW,
            NOW.plusMillis(20),
            20L,
            Map.of(),
            NOW.plusMillis(20)
        );
    }
}
