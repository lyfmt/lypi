package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class InlineTerminalRendererTest {
    private static final String SYNC_START = "\033[?2026h";
    private static final String SYNC_END = "\033[?2026l";

    @Test
    void firstBatchCommitsHistoryAndSurfaceInOneSynchronizedFlush() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo(80, 12);
        InlineTerminalRenderer renderer = new InlineTerminalRenderer(
            io,
            new InlineViewport(4, 2, 80, 12)
        );

        renderer.render(new TuiRenderBatch(
            List.of(new TerminalLine("final answer")),
            TuiRenderFrame.fromTextLines(List.of("> draft|CURSOR|", "status"))
        ));

        String output = io.output.toString();
        assertTrue(output.startsWith(SYNC_START));
        assertTrue(output.endsWith(SYNC_END));
        assertEquals(1, occurrences(output, "final answer"));
        assertEquals(1, io.flushCount);
        assertFalse(output.contains("\033[2J"));
        assertFalse(output.contains("\033[3J"));
        assertFalse(output.contains("\033[?1049"));
    }

    @Test
    void cursorOnlyChangeMovesHardwareCursorWithoutRewritingSurface() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo(80, 12);
        InlineTerminalRenderer renderer = new InlineTerminalRenderer(
            io,
            new InlineViewport(4, 2, 80, 12)
        );
        renderer.render(new TuiRenderBatch(
            List.of(new TerminalLine("final answer")),
            TuiRenderFrame.fromTextLines(List.of("status", "> a|CURSOR|bc"))
        ));
        io.resetOutput();

        renderer.render(new TuiRenderBatch(
            List.of(),
            TuiRenderFrame.fromTextLines(List.of("status", "> ab|CURSOR|c"))
        ));

        assertEquals("\033[7;5H", io.output.toString());
        assertFalse(io.output.toString().contains("final answer"));
        assertEquals(1, io.flushCount);
    }

    @Test
    void growingSurfaceScrollsOnlyCommittedRowsAboveOldSurface() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo(80, 12);
        InlineTerminalRenderer renderer = new InlineTerminalRenderer(
            io,
            new InlineViewport(8, 3, 80, 12)
        );
        renderer.render(new TuiRenderBatch(
            List.of(),
            TuiRenderFrame.fromTextLines(List.of("live", "> draft|CURSOR|", "status"))
        ));
        io.resetOutput();

        renderer.render(new TuiRenderBatch(
            List.of(),
            TuiRenderFrame.fromTextLines(List.of(
                "live-1", "live-2", "live-3", "live-4", "> draft|CURSOR|", "status"
            ))
        ));

        String output = io.output.toString();
        assertTrue(output.contains("\033[1;8r\033[8;1H\r\n\r\n\033[r"));
        assertFalse(output.contains("\033[1;9r"));
    }

    @Test
    void shorterSurfaceClearsOldTailWithoutNaturalScrolling() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo(80, 12);
        InlineTerminalRenderer renderer = new InlineTerminalRenderer(
            io,
            new InlineViewport(5, 3, 80, 12)
        );
        renderer.render(new TuiRenderBatch(
            List.of(),
            TuiRenderFrame.fromTextLines(List.of("live", "> draft|CURSOR|", "status-old"))
        ));
        io.resetOutput();

        renderer.render(new TuiRenderBatch(
            List.of(),
            TuiRenderFrame.fromTextLines(List.of("> draft|CURSOR|", "status-new"))
        ));

        String output = io.output.toString();
        assertTrue(output.contains("\033[8;1H\033[2K"));
        assertFalse(output.contains("\r\n"));
        assertFalse(output.contains("status-old"));
    }

    @Test
    void topConstrainedViewportUsesLinearFallbackAndReservesSurfaceRows() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo(20, 5);
        InlineTerminalRenderer renderer = new InlineTerminalRenderer(
            io,
            new InlineViewport(0, 2, 20, 5)
        );
        renderer.render(new TuiRenderBatch(
            List.of(),
            TuiRenderFrame.fromTextLines(List.of("> old-input|CURSOR|", "old-status"))
        ));
        io.resetOutput();

        renderer.render(new TuiRenderBatch(
            List.of(new TerminalLine("history-1")),
            TuiRenderFrame.fromTextLines(List.of("> new-input|CURSOR|", "new-status"))
        ));

        String output = io.output.toString();
        assertFalse(output.contains("\033[1;1r"));
        assertFalse(output.contains("old-input"));
        assertFalse(output.contains("old-status"));
        assertEquals(1, occurrences(output, "history-1"));
        String afterHistory = output.substring(output.indexOf("history-1") + "history-1".length());
        assertTrue(occurrences(afterHistory, "\r\n\033[2K") >= 2);
        assertTrue(output.contains("\033[2;1H\033[2K> new-input"));
        assertTrue(output.contains("\033[3;1H\033[2Knew-status"));
    }

    @Test
    void linearFallbackRedrawsUnchangedSurfaceAfterClearingIt() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo(20, 5);
        InlineTerminalRenderer renderer = new InlineTerminalRenderer(
            io,
            new InlineViewport(0, 2, 20, 5)
        );
        TuiRenderFrame surface = TuiRenderFrame.fromTextLines(List.of("> draft|CURSOR|", "status"));
        renderer.render(new TuiRenderBatch(List.of(), surface));
        io.resetOutput();

        renderer.render(new TuiRenderBatch(List.of(new TerminalLine("history")), surface));

        String output = io.output.toString();
        assertTrue(output.contains("\033[2;1H\033[2K> draft"));
        assertTrue(output.contains("\033[3;1H\033[2Kstatus"));
    }

    @Test
    void standardHistoryScrollRegionEndsAboveShiftedSurface() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo(80, 8);
        InlineTerminalRenderer renderer = new InlineTerminalRenderer(
            io,
            new InlineViewport(4, 2, 80, 8)
        );

        renderer.render(new TuiRenderBatch(
            List.of(new TerminalLine("history-1"), new TerminalLine("history-2")),
            TuiRenderFrame.fromTextLines(List.of("> draft|CURSOR|", "status"))
        ));

        String output = io.output.toString();
        assertTrue(output.contains("\033[1;6r"));
        assertFalse(output.contains("\033[1;7r"));
        assertTrue(output.contains("\033[7;1H\033[2K> draft"));
        assertTrue(output.contains("\033[8;1H\033[2Kstatus"));
    }

    @Test
    void fullWidthHistoryLineUsesOneLeadingCrLfWithoutExtraBlankLine() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo(10, 4);
        InlineTerminalRenderer renderer = new InlineTerminalRenderer(
            io,
            new InlineViewport(2, 2, 10, 4)
        );

        renderer.render(new TuiRenderBatch(
            List.of(new TerminalLine("1234567890")),
            TuiRenderFrame.fromTextLines(List.of("> |CURSOR|", "status"))
        ));

        String output = io.output.toString();
        assertEquals(1, occurrences(output, "\r\n"));
        assertTrue(output.contains("\r\n1234567890"));
        assertFalse(output.contains("123456789…"));
    }

    @Test
    void finishClearsSurfaceResetsRegionAndIsIdempotent() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo(80, 12);
        InlineTerminalRenderer renderer = new InlineTerminalRenderer(
            io,
            new InlineViewport(4, 2, 80, 12)
        );
        renderer.render(new TuiRenderBatch(
            List.of(),
            TuiRenderFrame.fromTextLines(List.of("> draft|CURSOR|", "status"))
        ));
        io.resetOutput();

        renderer.finish();
        renderer.finish();

        String output = io.output.toString();
        assertTrue(output.startsWith(SYNC_START + "\033[r"));
        assertTrue(output.contains("\033[5;1H\033[2K"));
        assertTrue(output.contains("\033[6;1H\033[2K"));
        assertTrue(output.endsWith("\033[5;1H" + SYNC_END));
        assertEquals(1, io.flushCount);
    }

    @Test
    void resizeRoundTripMovesViewportWithoutRewritingHistory() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo(80, 8);
        InlineTerminalRenderer renderer = new InlineTerminalRenderer(
            io,
            new InlineViewport(5, 3, 80, 8)
        );
        TuiRenderFrame surface = TuiRenderFrame.fromTextLines(List.of(
            "live", "> draft|CURSOR|", "status"
        ));
        renderer.render(new TuiRenderBatch(List.of(new TerminalLine("committed")), surface));
        io.resetOutput();

        io.setDimensions(60, 6);
        renderer.resize(60, 6);
        renderer.render(new TuiRenderBatch(List.of(), surface));

        String shrinkOutput = io.output.toString();
        assertTrue(shrinkOutput.contains("\033[1;5r\033[5;1H\r\n\r\n\033[r"));
        assertFalse(shrinkOutput.contains("committed"));
        assertFalse(shrinkOutput.contains("\033[2J"));
        assertFalse(shrinkOutput.contains("\033[3J"));

        io.resetOutput();
        io.setDimensions(80, 8);
        renderer.resize(80, 8);
        renderer.render(new TuiRenderBatch(List.of(), surface));

        String growOutput = io.output.toString();
        assertFalse(growOutput.contains("committed"));
        assertFalse(growOutput.contains("\r\n"));
        assertTrue(growOutput.contains("\033[6;1H\033[2Klive"));
        assertTrue(growOutput.contains("\033[8;1H\033[2Kstatus"));
    }

    @Test
    void rejectsCursorMarkerInHistoryBeforeWritingTerminalOutput() {
        RecordingTerminalIo io = new RecordingTerminalIo(80, 8);
        InlineTerminalRenderer renderer = new InlineTerminalRenderer(
            io,
            new InlineViewport(4, 2, 80, 8)
        );

        assertThrows(IllegalArgumentException.class, () -> renderer.render(new TuiRenderBatch(
            List.of(new TerminalLine("invalid |CURSOR| history")),
            TuiRenderFrame.fromTextLines(List.of("> draft|CURSOR|", "status"))
        )));

        assertEquals("", io.output.toString());
        assertEquals(0, io.flushCount);
    }

    @Test
    void finishEndsSynchronizedUpdateAndFlushesWhenSurfaceClearFails() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo(80, 8);
        InlineTerminalRenderer renderer = new InlineTerminalRenderer(
            io,
            new InlineViewport(4, 2, 80, 8)
        );
        renderer.render(new TuiRenderBatch(
            List.of(),
            TuiRenderFrame.fromTextLines(List.of("> draft|CURSOR|", "status"))
        ));
        io.resetOutput();
        io.failNextWriteOf("\033[2K");

        assertThrows(IOException.class, renderer::finish);
        renderer.finish();

        assertTrue(io.output.toString().startsWith(SYNC_START + "\033[r"));
        assertTrue(io.output.toString().endsWith(SYNC_END));
        assertEquals(1, io.flushCount);
    }

    private int occurrences(String value, String needle) {
        int count = 0;
        int from = 0;
        while ((from = value.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    private static final class RecordingTerminalIo implements TerminalIo {
        private final StringBuilder output = new StringBuilder();
        private int width;
        private int height;
        private int flushCount;
        private String failingValue;

        private RecordingTerminalIo(int width, int height) {
            this.width = width;
            this.height = height;
        }

        @Override
        public AutoCloseable enterRawMode() {
            return () -> {
            };
        }

        @Override
        public void write(String value) throws IOException {
            if (value.equals(failingValue)) {
                failingValue = null;
                throw new IOException("write failed");
            }
            output.append(value);
        }

        @Override
        public void flush() {
            flushCount++;
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }

        @Override
        public AutoCloseable onResize(Runnable callback) {
            return () -> {
            };
        }

        @Override
        public AutoCloseable onInterrupt(Runnable callback) {
            return () -> {
            };
        }

        private void resetOutput() {
            output.setLength(0);
            flushCount = 0;
        }

        private void setDimensions(int width, int height) {
            this.width = width;
            this.height = height;
        }

        private void failNextWriteOf(String value) {
            failingValue = value;
        }
    }
}
