package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class TerminalHistoryWriterTest {
    @Test
    void insertsHistoryAboveViewportUsingScrollRegion() throws IOException {
        RecordingTerminalIo io = new RecordingTerminalIo(80, 8);
        TerminalHistoryWriter writer = new TerminalHistoryWriter(io);

        writer.insertAboveViewport(List.of("line 1", "line 2"), new TuiViewportArea(5, 4));

        String output = io.output.toString();
        assertTrue(output.contains("\033[1;4r"));
        assertTrue(output.contains("\033[4;1H"));
        assertTrue(output.contains("\r\nline 1"));
        assertTrue(output.contains("\r\nline 2"));
        assertTrue(output.contains("\033[r"));
        assertFalse(output.contains("> "));
    }

    @Test
    void movesViewportDownWhenThereIsScreenSpaceBelowIt() throws IOException {
        RecordingTerminalIo io = new RecordingTerminalIo(80, 8);
        TerminalHistoryWriter writer = new TerminalHistoryWriter(io);

        TuiViewportArea area = writer.insertAboveViewport(List.of("history"), new TuiViewportArea(3, 4));

        assertTrue(io.output.toString().contains("\033[3;8r"));
        assertTrue(io.output.toString().contains("\033[3;1H\033M"));
        assertEquals(new TuiViewportArea(4, 4), area);
    }

    private static final class RecordingTerminalIo implements TerminalIo {
        private final int width;
        private final int height;
        private final StringBuilder output = new StringBuilder();

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
