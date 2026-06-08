package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class TerminalSessionTest {
    @Test
    void openEntersInteractiveTerminalModesAndCloseRestoresThem() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();

        TerminalSession session = TerminalSession.open(io);

        assertTrue(io.rawModeEntered);
        assertEquals(
            "\033[?1049h\033[?2004h\033[?25l\033[?u\033[>4;2m",
            io.output.toString()
        );

        session.close();

        assertTrue(io.rawModeRestored);
        assertEquals(
            "\033[?1049h\033[?2004h\033[?25l\033[?u\033[>4;2m"
                + "\033[>4m\033[?u\033[?25h\033[?2004l\033[?1049l",
            io.output.toString()
        );
    }

    @Test
    void closeIsIdempotent() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        TerminalSession session = TerminalSession.open(io);

        session.close();
        session.close();

        assertEquals(1, io.restoreCount);
    }

    private static final class RecordingTerminalIo implements TerminalIo {
        private final StringBuilder output = new StringBuilder();
        private boolean rawModeEntered;
        private boolean rawModeRestored;
        private int restoreCount;

        @Override
        public AutoCloseable enterRawMode() {
            rawModeEntered = true;
            return () -> {
                rawModeRestored = true;
                restoreCount++;
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
        public AutoCloseable onResize(Runnable callback) throws IOException {
            return () -> {
            };
        }
    }
}
