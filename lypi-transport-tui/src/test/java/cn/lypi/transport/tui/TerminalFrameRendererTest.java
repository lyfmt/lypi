package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class TerminalFrameRendererTest {
    @Test
    void firstFrameWritesContentWithoutClearingScreenAndPositionsHardwareCursor() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);

        renderer.render(List.of("hello", "world|CURSOR|"));

        assertEquals("hello\nworld\033[2;6H", io.output.toString());
        assertFalse(io.output.toString().contains("\033[H\033[J"));
    }

    @Test
    void appendsTailLinesWithoutHomeAndClear() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);

        renderer.render(List.of("hello"));
        io.output.setLength(0);
        renderer.render(List.of("hello", "new line"));

        assertEquals("\nnew line", io.output.toString());
        assertFalse(io.output.toString().contains("\033[H\033[J"));
    }

    @Test
    void inputEditRewritesOnlyChangedVisibleLine() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);

        renderer.render(List.of("history", "> a|CURSOR|"));
        io.output.setLength(0);
        renderer.render(List.of("history", "> ab|CURSOR|"));

        assertEquals("\033[?2026h\033[2;1H\033[2K> ab\033[2;5H\033[?2026l", io.output.toString());
    }

    @Test
    void overflowInputEditUsesPhysicalViewportRow() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        io.height = 4;
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);

        renderer.render(List.of("one", "two", "three", "> a|CURSOR|", "status"));
        io.output.setLength(0);
        renderer.render(List.of("one", "two", "three", "> ab|CURSOR|", "status"));

        assertEquals("\033[?2026h\033[3;1H\033[2K> ab\033[3;5H\033[?2026l", io.output.toString());
    }

    @Test
    void overflowCursorOnlyMoveUsesPhysicalViewportRow() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        io.height = 4;
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);

        renderer.render(List.of("one", "two", "three", "> a|CURSOR|b", "status"));
        io.output.setLength(0);
        renderer.render(List.of("one", "two", "three", "> ab|CURSOR|", "status"));

        assertEquals("\033[3;5H", io.output.toString());
    }

    @Test
    void overflowStatusLinePatchUsesPhysicalBottomRow() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        io.height = 4;
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);

        renderer.render(List.of("one", "two", "three", "> input|CURSOR|", "status A"));
        io.output.setLength(0);
        renderer.render(List.of("one", "two", "three", "> input|CURSOR|", "status B"));

        assertEquals("\033[?2026h\033[4;1H\033[2Kstatus B\033[3;8H\033[?2026l", io.output.toString());
    }

    @Test
    void multilineInputPatchUsesBottomAnchoredRows() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        io.height = 5;
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);

        renderer.render(List.of("line4", "> abcdef", "ghij|CURSOR|", "status"));
        io.output.setLength(0);
        renderer.render(List.of("line4", "> abcdef", "ghijX|CURSOR|", "status"));

        assertEquals("\033[?2026h\033[3;1H\033[2KghijX\033[3;6H\033[?2026l", io.output.toString());
    }

    @Test
    void middleLineChangePatchesFromFirstChangedLine() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);

        renderer.render(List.of("one", "two", "three"));
        io.output.setLength(0);
        renderer.render(List.of("one", "TWO", "three"));

        assertEquals("\033[?2026h\033[2;1H\033[2KTWO\033[?2026l", io.output.toString());
    }

    @Test
    void tailChangePatchesLastLineWithoutAppendingDuplicateTranscript() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);

        renderer.render(List.of("one", "two"));
        io.output.setLength(0);
        renderer.render(List.of("one", "TWO"));

        assertEquals("\033[?2026h\033[2;1H\033[2KTWO\033[?2026l", io.output.toString());
    }

    @Test
    void contentShrinkUsesFullRenderToClearStaleRows() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);

        renderer.render(List.of("one", "two", "three"));
        io.output.setLength(0);
        renderer.render(List.of("one"));

        assertEquals("\033[?2026h\033[2J\033[H\033[3Jone\033[?2026l", io.output.toString());
    }

    @Test
    void widthOrHeightChangeUsesFullRender() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);

        renderer.render(List.of("one"));
        io.output.setLength(0);
        io.width = 100;
        renderer.render(List.of("one"));

        assertEquals("\033[?2026h\033[2J\033[H\033[3Jone\033[?2026l", io.output.toString());
    }

    @Test
    void changeAbovePreviousViewportUsesFullRender() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        io.height = 2;
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);

        renderer.render(List.of("one", "two", "three"));
        io.output.setLength(0);
        renderer.render(List.of("ONE", "two", "three"));

        assertEquals("\033[?2026h\033[2J\033[H\033[3JONE\ntwo\nthree\033[?2026l", io.output.toString());
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
        public AutoCloseable onResize(Runnable callback) throws IOException {
            return () -> {
            };
        }
    }
}
