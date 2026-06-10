package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TerminalSessionTest {
    @Test
    void openEntersInteractiveTerminalModesAndCloseRestoresThem() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();

        TerminalSession session = TerminalSession.open(io);

        assertTrue(io.rawModeEntered);
        assertEquals(
            "\033[?2004h\033[?25l\033[?u\033[>4;2m",
            io.output.toString()
        );

        session.close();

        assertTrue(io.rawModeRestored);
        assertEquals(
            "\033[?2004h\033[?25l\033[?u\033[>4;2m"
                + "\033[>4m\033[?u\033[?25h\033[?2004l\n",
            io.output.toString()
        );
    }

    @Test
    void closeMovesBelowRenderedContentWhenRendererReportedRows() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        TerminalSession session = TerminalSession.open(io);

        session.updateRenderedRows(3);
        session.close();

        assertTrue(io.output.toString().endsWith("\033[3;1H\n"));
    }

    @Test
    void closeIsIdempotent() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        TerminalSession session = TerminalSession.open(io);

        session.close();
        session.close();

        assertEquals(1, io.restoreCount);
    }

    @Test
    void openRegistersInterruptHandlerAndCloseRestoresIt() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        AtomicInteger interrupts = new AtomicInteger();

        TerminalSession session = TerminalSession.open(io, () -> {
        }, interrupts::incrementAndGet);

        io.interruptCallback.run();
        assertEquals(1, interrupts.get());

        session.close();

        assertTrue(io.interruptHandlerRestored);
    }

    @Test
    void openFailureRestoresRawModeAndResizeHandler() {
        FailingTerminalIo io = new FailingTerminalIo();

        assertThrows(IOException.class, () -> TerminalSession.open(io));

        assertTrue(io.rawModeRestored);
        assertTrue(io.resizeHandlerRestored);
        assertTrue(io.interruptHandlerRestored);
    }

    private static final class RecordingTerminalIo implements TerminalIo {
        private final StringBuilder output = new StringBuilder();
        private boolean rawModeEntered;
        private boolean rawModeRestored;
        private boolean interruptHandlerRestored;
        private Runnable interruptCallback = () -> {
        };
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
        public int width() {
            return 80;
        }

        @Override
        public int height() {
            return 24;
        }

        @Override
        public AutoCloseable onResize(Runnable callback) throws IOException {
            return () -> {
            };
        }

        @Override
        public AutoCloseable onInterrupt(Runnable callback) {
            interruptCallback = callback;
            return () -> interruptHandlerRestored = true;
        }
    }

    private static final class FailingTerminalIo implements TerminalIo {
        private boolean rawModeRestored;
        private boolean resizeHandlerRestored;
        private boolean interruptHandlerRestored;

        @Override
        public AutoCloseable enterRawMode() {
            return () -> rawModeRestored = true;
        }

        @Override
        public void write(String value) throws IOException {
            throw new IOException("write failed");
        }

        @Override
        public void flush() {
        }

        @Override
        public int width() {
            return 80;
        }

        @Override
        public int height() {
            return 24;
        }

        @Override
        public AutoCloseable onResize(Runnable callback) {
            return () -> resizeHandlerRestored = true;
        }

        @Override
        public AutoCloseable onInterrupt(Runnable callback) {
            return () -> interruptHandlerRestored = true;
        }
    }
}
