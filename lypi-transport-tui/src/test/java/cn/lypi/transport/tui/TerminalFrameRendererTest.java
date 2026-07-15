package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TerminalFrameRendererTest {
    private static final String SYNC_START = "\033[?2026h";
    private static final String SYNC_END = "\033[?2026l";
    private static final String FULL_CLEAR = "\033[2J\033[H";

    @Test
    void rejectsFramesTallerThanTerminalWithoutWritingPartialOutput() {
        RecordingTerminalIo io = new RecordingTerminalIo();
        io.height = 5;
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);

        assertThrows(IllegalArgumentException.class, () -> renderer.render(
            IntStream.rangeClosed(1, io.height + 1).mapToObj(String::valueOf).toList()
        ));

        assertEquals("", io.output.toString());
    }

    @Test
    void firstFrameClearsAlternateScreenAndUsesAbsoluteRows() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);

        renderer.render(List.of("hello", "world|CURSOR|"));

        assertEquals(
            SYNC_START
                + FULL_CLEAR
                + "\033[1;1Hhello"
                + "\033[2;1Hworld"
                + "\033[2;6H"
                + SYNC_END,
            io.output.toString()
        );
    }

    @Test
    void fixedFramePatchUsesPhysicalRowsWithoutTerminalScrolling() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        io.height = 6;
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);
        renderer.render(List.of("history-1", "history-2", "separator", "live-old", "> |CURSOR|", "status"));
        io.output.setLength(0);

        renderer.render(List.of("history-2", "history-3", "separator", "live-new", "> |CURSOR|", "status"));

        assertEquals(
            SYNC_START
                + "\033[1;1H\033[2Khistory-2"
                + "\033[2;1H\033[2Khistory-3"
                + "\033[4;1H\033[2Klive-new"
                + "\033[5;3H"
                + SYNC_END,
            io.output.toString()
        );
        assertFalse(io.output.toString().contains("\r\n"));
        assertFalse(io.output.toString().contains("\n"));
        assertFalse(io.output.toString().contains(FULL_CLEAR));
        assertFalse(io.output.toString().contains("\033[6;1H"));
    }

    @Test
    void cursorOnlyChangeMovesHardwareCursorWithoutRewritingLine() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);
        renderer.render(List.of("status", "> a|CURSOR|bc"));
        io.output.setLength(0);

        renderer.render(List.of("status", "> ab|CURSOR|c"));

        assertEquals("\033[2;5H", io.output.toString());
    }

    @Test
    void shorterFrameClearsTrailingOldRows() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);
        renderer.render(List.of("one", "two", "three|CURSOR|"));
        io.output.setLength(0);

        renderer.render(List.of("one", "two|CURSOR|"));

        assertEquals(
            SYNC_START
                + "\033[3;1H\033[2K"
                + "\033[2;4H"
                + SYNC_END,
            io.output.toString()
        );
    }

    @Test
    void resizeClearsAndRendersWholeCurrentFrame() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);
        renderer.render(List.of("one", "two|CURSOR|"));
        io.output.setLength(0);
        io.width = 40;

        renderer.render(List.of("one", "two|CURSOR|"));

        assertTrue(io.output.toString().startsWith(SYNC_START + FULL_CLEAR));
        assertTrue(io.output.toString().contains("\033[1;1Hone"));
        assertTrue(io.output.toString().contains("\033[2;1Htwo"));
        assertTrue(io.output.toString().endsWith("\033[2;4H" + SYNC_END));
    }

    @Test
    void terminalWritesTruncatePhysicalLinesToCurrentWidth() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        io.width = 5;
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);

        renderer.render(TuiRenderFrame.fromTextLines(List.of("abcdefgh")));

        assertTrue(io.output.toString().contains("\033[1;1Habcd…"));
        assertFalse(io.output.toString().contains("abcdef"));
    }

    @Test
    void unchangedFrameWithoutCursorProducesNoOutput() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);
        renderer.render(List.of("unchanged"));
        io.output.setLength(0);

        renderer.render(List.of("unchanged"));

        assertEquals("", io.output.toString());
    }

    private static final class RecordingTerminalIo implements TerminalIo {
        private final StringBuilder output = new StringBuilder();
        private int width = 80;
        private int height = 24;

        @Override
        public AutoCloseable enterRawMode() {
            return () -> {
            };
        }

        @Override
        public void write(String value) {
            output.append(value);
        }

        @Override
        public void flush() {
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
    }
}
