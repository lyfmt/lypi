package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class TerminalFrameRendererTest {
    @Test
    void firstFrameWritesContentWithoutClearingScreenAndPositionsHardwareCursor() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);

        renderer.render(List.of("hello", "world|CURSOR|"));

        assertEquals("\033[1;1H\033[2Khello\033[2;1H\033[2Kworld\033[2;6H", io.output.toString());
        assertFalse(io.output.toString().contains("\n"));
        assertFalse(io.output.toString().contains("\033[H\033[J"));
    }

    @Test
    void firstFrameWithStartupPaddingClearsScreenPadsToViewportBottomAndPositionsHardwareCursor() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        io.height = 4;
        TerminalFrameRenderer renderer = TerminalFrameRenderer.withStartupPadding(io, rows -> {
        });

        renderer.render(List.of("hello", "world|CURSOR|"));

        String output = io.output.toString();
        assertTrue(output.startsWith("\033[?2026h\033[2J\033[H"));
        assertTrue(output.endsWith("\033[3;1H\033[2Khello\033[4;1H\033[2Kworld\033[4;6H\033[?2026l"));
        assertFalse(output.contains("\n"));
    }

    @Test
    void firstFrameWithStartupPaddingRendersWelcomeScreen() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        io.width = 80;
        io.height = 11;
        TerminalFrameRenderer renderer = TerminalFrameRenderer.withStartupPadding(io, rows -> {
        });

        renderer.render(List.of("hello", "> |CURSOR|", "status"));

        String plainOutput = stripAnsi(io.output.toString());
        assertTrue(plainOutput.contains("LY-PI"));
        assertTrue(plainOutput.contains("coding agent"));
        assertFalse(plainOutput.contains("local-first"));
        assertTrue(plainOutput.contains("██████╗ "));
        assertTrue(plainOutput.contains("██╔══██╗"));
        assertTrue(plainOutput.contains("██████╔╝"));
        assertTrue(plainOutput.contains("hello"));
        assertTrue(plainOutput.contains("> "));
        assertTrue(plainOutput.contains("status"));
        assertTrue(io.output.toString().endsWith("\033[10;3H\033[?2026l"));
        assertFalse(io.output.toString().contains("\n"));

        for (String line : positionedPlainRows(io.output.toString())) {
            assertTrue(AnsiWidth.displayWidth(line) <= io.width, () -> "line exceeds terminal width: " + line);
        }
    }

    @Test
    void startupPaddingRemainsPartOfLinearScrollbackAfterFirstFrame() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        io.height = 4;
        TerminalFrameRenderer renderer = TerminalFrameRenderer.withStartupPadding(io, rows -> {
        });

        renderer.render(List.of("hello", "> |CURSOR|"));
        io.output.setLength(0);
        renderer.render(List.of("hello", "assistant", "> |CURSOR|"));

        String output = io.output.toString();
        assertFalse(output.contains("\r\n"), escaped(output));
        assertTrue(output.contains("\033[2;1H\033[2Khello"), escaped(output));
        assertTrue(output.contains("\033[3;1H\033[2Kassistant"), escaped(output));
        assertTrue(output.contains("\033[4;1H\033[2K> "), escaped(output));
        assertTrue(output.endsWith("\033[4;3H\033[?2026l"), escaped(output));
    }

    @Test
    void terminalWritesTruncateLongPhysicalLinesToTerminalWidth() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        io.width = 10;
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);

        renderer.render(List.of("0123456789abcdef", "> |CURSOR|"));

        String output = io.output.toString();
        assertFalse(output.contains("0123456789abcdef"));
        assertTrue(output.contains("012345678…"));
    }

    @Test
    void tailGrowthPatchesByAbsoluteRowsWithoutNaturalNewline() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);

        renderer.render(List.of("hello"));
        io.output.setLength(0);
        renderer.render(List.of("hello", "new line"));

        assertEquals("\033[?2026h\033[2;1H\033[2Knew line\033[?2026l", io.output.toString());
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
    void bottomActivityChangeAfterOverflowPatchesVisibleRowsWithoutAppendingToScrollback() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        io.height = 4;
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);

        renderer.render(List.of("one", "two", "three", "> |CURSOR|", "status A"));
        io.output.setLength(0);
        renderer.render(List.of("one", "two", "three", "> draft|CURSOR|", "status A"));

        assertEquals("\033[?2026h\033[3;1H\033[2K> draft\033[3;8H\033[?2026l", io.output.toString());
    }

    @Test
    void transcriptAppendThatOverflowsPatchesCurrentViewport() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        io.height = 4;
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);

        renderer.render(List.of("one", "two", "> |CURSOR|", "status"));
        io.output.setLength(0);
        renderer.render(List.of("one", "two", "three", "> |CURSOR|", "status"));

        String output = io.output.toString();
        assertFalse(output.contains("\r\n"));
        assertFalse(output.contains("\033[1;2r"));
        assertTrue(output.contains("\033[1;1H\033[2Ktwo"));
        assertTrue(output.contains("\033[2;1H\033[2Kthree"));
        assertTrue(output.contains("\033[3;1H\033[2K> "));
        assertTrue(output.contains("\033[4;1H\033[2Kstatus"));
        assertTrue(output.endsWith("\033[3;3H\033[?2026l"));
    }

    @Test
    void transcriptInsertBeforeTailRewritesLinearTail() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        io.height = 8;
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);

        renderer.render(List.of(
            "assistant old",
            "──",
            "> draft|CURSOR|",
            "──",
            "session PLAN"
        ));
        io.output.setLength(0);
        renderer.render(List.of(
            "assistant old",
            "tool running read",
            "──",
            "> draft|CURSOR|",
            "──",
            "session PLAN"
        ));

        String output = io.output.toString();
        assertTrue(output.contains("\033[2;1H\033[2Ktool running read"));
        assertTrue(output.contains("\033[3;1H\033[2K──"));
        assertTrue(output.contains("\033[4;1H\033[2K> draft"));
        assertTrue(output.contains("\033[5;1H\033[2K──"));
        assertTrue(output.contains("\033[6;1H\033[2Ksession PLAN"));
        assertTrue(output.endsWith("\033[4;8H\033[?2026l"));
    }

    @Test
    void transcriptAppendAfterOverflowPatchesCurrentViewport() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        io.height = 4;
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);

        renderer.render(List.of("one", "two", "three", "> |CURSOR|", "status"));
        io.output.setLength(0);
        renderer.render(List.of("one", "two", "three", "four", "> |CURSOR|", "status"));

        String output = io.output.toString();
        assertFalse(output.contains("\r\n"));
        assertFalse(output.contains("\033[1;2r"));
        assertTrue(output.contains("\033[1;1H\033[2Kthree"));
        assertTrue(output.contains("\033[2;1H\033[2Kfour"));
        assertTrue(output.contains("\033[3;1H\033[2K> "));
        assertTrue(output.contains("\033[4;1H\033[2Kstatus"));
        assertTrue(output.endsWith("\033[3;3H\033[?2026l"));
    }

    @Test
    void transcriptAppendWithBottomUiPatchesCurrentViewport() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        io.height = 6;
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);

        renderer.render(List.of(
            "assistant old",
            "tools: read x1 (Ctrl+O details)",
            "──",
            "> |CURSOR|",
            "──",
            "session PLAN"
        ));
        io.output.setLength(0);
        renderer.render(List.of(
            "assistant old",
            "tools: read x1 (Ctrl+O details)",
            "done write write {content=问题：50米洗车店我该开车去还是走路去",
            "  writing",
            "  written bytes 洗车店.md",
            "assistant done",
            "──",
            "> |CURSOR|",
            "──",
            "session PLAN"
        ));

        String output = io.output.toString();
        assertFalse(output.contains("\r\n"));
        assertFalse(output.contains("\033[1;3r"));
        assertTrue(output.contains("\033[1;1H\033[2K  written bytes 洗车店.md"));
        assertTrue(output.contains("\033[2;1H\033[2Kassistant done"));
        assertTrue(output.contains("\033[3;1H\033[2K──"));
        assertTrue(output.contains("\033[4;1H\033[2K> "));
        assertTrue(output.contains("\033[5;1H\033[2K──"));
        assertTrue(output.contains("\033[6;1H\033[2Ksession PLAN"));
    }

    @Test
    void toolExpansionOverflowUsesViewportPatchWithoutScrollingChrome() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        io.height = 6;
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);

        renderer.render(List.of(
            "assistant old",
            "tools: read x1 (Ctrl+O details)",
            "──",
            "> |CURSOR|",
            "──",
            "session PLAN"
        ));
        io.output.setLength(0);
        renderer.render(List.of(
            "assistant old",
            "done read AGENTS.md:1-200",
            "done glob {path=., pattern=**/*}",
            "assistant answer",
            "──",
            "> |CURSOR|",
            "──",
            "session PLAN"
        ));

        String output = io.output.toString();
        assertFalse(output.contains("\r\n"));
        assertFalse(output.contains("done read AGENTS.md:1-200"));
        assertTrue(output.contains("\033[1;1H\033[2Kdone glob {path=., pattern=**/*}"));
        assertTrue(output.contains("\033[2;1H\033[2Kassistant answer"));
        assertTrue(output.contains("\033[3;1H\033[2K──"));
        assertTrue(output.contains("\033[4;1H\033[2K> "));
        assertTrue(output.contains("\033[5;1H\033[2K──"));
        assertTrue(output.contains("\033[6;1H\033[2Ksession PLAN"));
        assertTrue(output.contains("\033[4;3H"), escaped(output));
    }

    @Test
    void toolProgressAppendAfterOverflowPatchesCurrentViewport() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        io.height = 5;
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);

        renderer.render(List.of(
            "tool running bash: test",
            "  old detail",
            "──",
            "> |CURSOR|",
            "status"
        ));
        io.output.setLength(0);
        renderer.render(List.of(
            "tool running bash: test",
            "  old detail",
            "  new detail",
            "──",
            "> |CURSOR|",
            "status"
        ));

        String output = io.output.toString();
        assertFalse(output.contains("\r\n"));
        assertTrue(output.contains("\033[1;1H\033[2K  old detail"));
        assertTrue(output.contains("\033[2;1H\033[2K  new detail"));
        assertTrue(output.contains("\033[3;1H\033[2K──"));
        assertTrue(output.contains("\033[4;1H\033[2K> "));
        assertTrue(output.contains("\033[5;1H\033[2Kstatus"));
    }

    @Test
    void viewportScrollPatchDoesNotRewriteRowsThatScrolledOutOfView() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        io.height = 5;
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);

        renderer.render(List.of(
            "tool done glob: Glob",
            "  matched AGENTS.md",
            "──",
            "> |CURSOR|",
            "──",
            "session PLAN"
        ));
        io.output.setLength(0);
        renderer.render(List.of(
            "tool done glob: Glob",
            "  matched AGENTS.md",
            "tool done read: Read",
            "  File: AGENTS.md",
            "assistant answer",
            "──",
            "> |CURSOR|",
            "──",
            "session PLAN"
        ));

        String output = io.output.toString();
        assertFalse(output.contains("tool done glob: Glob"));
        assertFalse(output.contains("matched AGENTS.md"));
        assertFalse(output.contains("tool done read: Read"), escaped(output));
        assertTrue(output.contains("assistant answer"), escaped(output));
    }

    @Test
    void chromeGrowthWithoutTranscriptAppendDoesNotScrollTerminal() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        io.height = 5;
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);

        renderer.render(List.of(
            "line1",
            "line2",
            "line3",
            "line4",
            "> |CURSOR|",
            "status"
        ));
        io.output.setLength(0);
        renderer.render(List.of(
            "line1",
            "line2",
            "line3",
            "line4",
            "> wrapped input",
            "continuation|CURSOR|",
            "status"
        ));

        String output = io.output.toString();
        assertFalse(output.contains("\r\n"));
        assertFalse(output.contains("\n"));
        assertTrue(output.contains("\033[3;1H\033[2K> wrapped input"));
        assertTrue(output.contains("\033[4;1H\033[2Kcontinuation"));
        assertTrue(output.contains("\033[5;1H\033[2Kstatus"));
    }

    @Test
    void contentShrinkPatchesVisibleRowsWithoutClearingTerminalScrollback() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);

        renderer.render(List.of("one", "two", "three"));
        io.output.setLength(0);
        renderer.render(List.of("one"));

        assertTrue(io.output.toString().startsWith("\033[?2026h\033[1;1H\033[2Kone"));
        assertTrue(io.output.toString().contains("\033[3;1H\033[2K"));
        assertTrue(io.output.toString().endsWith("\033[?2026l"));
        assertFalse(io.output.toString().contains("\033[2J\033[H"));
    }

    @Test
    void transientRuntimeLineRemovalPatchesBottomChromeWithoutFullRedraw() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        io.height = 6;
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);

        renderer.render(List.of(
            "test 1",
            "test 2",
            "test 3",
            "test 4",
            "test 5",
            "test 6",
            "test 7",
            "test 8",
            "test 9",
            "test 10",
            "test 11",
            "test 12",
            "test 13",
            "test 14",
            "test 15",
            "test 16",
            "test 17",
            "test 18",
            "test 19",
            "test 20",
            "· turn running abc",
            "──",
            "> |CURSOR|",
            "──",
            "session running"
        ));
        io.output.setLength(0);
        renderer.render(List.of(
            "test 1",
            "test 2",
            "test 3",
            "test 4",
            "test 5",
            "test 6",
            "test 7",
            "test 8",
            "test 9",
            "test 10",
            "test 11",
            "test 12",
            "test 13",
            "test 14",
            "test 15",
            "test 16",
            "test 17",
            "test 18",
            "test 19",
            "test 20",
            "──",
            "> |CURSOR|",
            "──",
            "session PLAN"
        ));

        assertEquals(
            "\033[?2026h"
                + "\033[1;1H\033[2Ktest 19"
                + "\033[2;1H\033[2Ktest 20"
                + "\033[3;1H\033[2K──"
                + "\033[4;1H\033[2K> "
                + "\033[5;1H\033[2K──"
                + "\033[6;1H\033[2Ksession PLAN"
                + "\033[4;3H"
                + "\033[?2026l",
            io.output.toString()
        );
        assertFalse(io.output.toString().contains("\033[2J\033[H"));
    }

    @Test
    void shrinkAfterLargeExpandedFrameUsesCurrentTailViewport() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        io.height = 5;
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);

        renderer.render(List.of(
            "tool header",
            "  line 1",
            "  line 2",
            "  line 3",
            "  line 4",
            "  line 5",
            "  line 6",
            "──",
            "> |CURSOR|",
            "status"
        ));
        io.output.setLength(0);
        renderer.render(List.of(
            "tools: bash x1 (Ctrl+O details)",
            "──",
            "> |CURSOR|",
            "status"
        ));

        String output = io.output.toString();
        assertTrue(output.contains("\033[1;1H\033[2Ktools: bash x1 (Ctrl+O details)"));
        assertTrue(output.contains("\033[2;1H\033[2K──"));
        assertTrue(output.contains("\033[3;1H\033[2K> "));
        assertTrue(output.contains("\033[4;1H\033[2Kstatus"));
        assertFalse(output.contains("line 6"));
    }

    @Test
    void widthOrHeightChangeUsesFullRenderWithoutClearingTerminalScrollback() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);

        renderer.render(List.of("one"));
        io.output.setLength(0);
        io.width = 100;
        renderer.render(List.of("one"));

        assertEquals("\033[?2026h\033[2J\033[H\033[1;1H\033[2Kone\033[?2026l", io.output.toString());
        assertFalse(io.output.toString().contains("\n"));
    }

    @Test
    void changeAbovePreviousViewportUsesFullRender() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        io.height = 2;
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);

        renderer.render(List.of("one", "two", "three"));
        io.output.setLength(0);
        renderer.render(List.of("ONE", "two", "three"));

        assertEquals(
            "\033[?2026h\033[2J\033[H"
                + "\033[1;1H\033[2Ktwo"
                + "\033[2;1H\033[2Kthree"
                + "\033[?2026l",
            io.output.toString()
        );
        assertFalse(io.output.toString().contains("\n"));
    }

    @Test
    void firstFrameOnlyWritesVisibleViewportRows() throws Exception {
        RecordingTerminalIo io = new RecordingTerminalIo();
        io.height = 3;
        TerminalFrameRenderer renderer = new TerminalFrameRenderer(io);

        renderer.render(List.of("one", "two", "three", "four|CURSOR|"));

        assertEquals(
            "\033[1;1H\033[2Ktwo"
                + "\033[2;1H\033[2Kthree"
                + "\033[3;1H\033[2Kfour"
                + "\033[3;5H",
            io.output.toString()
        );
        assertFalse(io.output.toString().contains("\n"));
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

        @Override
        public AutoCloseable onInterrupt(Runnable callback) {
            return () -> {
            };
        }
    }

    private static String stripAnsi(String value) {
        return value.replaceAll("\\u001B\\[[0-9;?]*[A-Za-z]", "");
    }

    private static String escaped(String value) {
        return value.replace("\033", "\\e").replace("\r", "\\r").replace("\n", "\\n");
    }

    private static List<String> positionedPlainRows(String output) {
        List<String> rows = new java.util.ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("\\u001B\\[[0-9]+;1H\\u001B\\[2K([^\\u001B]*)")
            .matcher(output);
        while (matcher.find()) {
            rows.add(stripAnsi(matcher.group(1)));
        }
        return rows;
    }
}
