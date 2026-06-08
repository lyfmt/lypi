package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class TerminalFrameRendererTest {
    @Test
    void rendersFrameWithSynchronizedOutputAndHardwareCursor() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);

        renderer.render(List.of("hello", "world|CURSOR|"));

        assertEquals("\033[?2026hhello\nworld\033[2;6H\033[?2026l", io.output.toString());
    }

    @Test
    void omitsCursorMoveWhenNoMarkerExists() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);

        renderer.render(List.of("hello"));

        assertEquals("\033[?2026hhello\033[?2026l", io.output.toString());
    }

    private static final class RecordingTerminalIo implements TerminalIo {
        private final StringBuilder output = new StringBuilder();

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
        public AutoCloseable onResize(Runnable callback) throws IOException {
            return () -> {
            };
        }
    }
}
