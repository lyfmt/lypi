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
            "\033[?1049h\033[?1000h\033[?1006h\033[?2004h\033[?25l\033[>4;2m",
            io.output.toString()
        );

        session.close();

        assertTrue(io.rawModeRestored);
        assertEquals(
            "\033[?1049h\033[?1000h\033[?1006h\033[?2004h\033[?25l\033[>4;2m"
                + "\033[>4m\033[?2004l\033[?1006l\033[?1000l\033[?1049l\033[?25h",
            io.output.toString()
        );
    }

    @Test
    void terminalModesDoNotQueryKittyKeyboardProtocol() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();

        TerminalSession session = TerminalSession.open(io);
        session.close();

        assertTrue(!io.output.toString().contains("\033[?u"));
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
    void openFailureLeavesAlternateScreenAndRestoresTerminalResources() {
        FailingTerminalIo io = new FailingTerminalIo(2);

        assertThrows(IOException.class, () -> TerminalSession.open(io));

        assertEquals(
            "\033[?1049h\033[>4m\033[?2004l\033[?1006l\033[?1000l\033[?1049l\033[?25h",
            io.output.toString()
        );
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
        private final StringBuilder output = new StringBuilder();
        private final int failingWrite;
        private boolean rawModeRestored;
        private boolean resizeHandlerRestored;
        private boolean interruptHandlerRestored;
        private int writeCount;

        private FailingTerminalIo(int failingWrite) {
            this.failingWrite = failingWrite;
        }

        @Override
        public AutoCloseable enterRawMode() {
            return () -> rawModeRestored = true;
        }

        @Override
        public void write(String value) throws IOException {
            writeCount++;
            if (writeCount == failingWrite) {
                throw new IOException("write failed");
            }
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
        public AutoCloseable onResize(Runnable callback) {
            return () -> resizeHandlerRestored = true;
        }

        @Override
        public AutoCloseable onInterrupt(Runnable callback) {
            return () -> interruptHandlerRestored = true;
        }
    }
}
