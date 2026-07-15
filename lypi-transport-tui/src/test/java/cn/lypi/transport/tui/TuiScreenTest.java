package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TuiScreenTest {
    @Test
    void retainsOnlyLatestFiveHundredPhysicalHistoryLines() {
        TuiScreen screen = new TuiScreen(10);
        screen.setTranscript(IntStream.rangeClosed(1, 501)
            .mapToObj(index -> "line-" + index)
            .toList());

        assertEquals(500, screen.retainedLineCount());
        screen.scrollUp(1_000);
        assertEquals("line-2", screen.visibleTranscript().getFirst());
    }

    @Test
    void preservesScrolledContentWhenBoundedWindowReceivesAnotherLine() {
        TuiScreen screen = new TuiScreen(10);
        screen.setTranscript(historyLines(1, 500));
        screen.scrollUp(5);
        List<String> visibleBeforeAppend = screen.visibleTranscript();

        screen.setTranscript(historyLines(1, 501));

        assertEquals(6, screen.linesBelow());
        assertEquals(visibleBeforeAppend, screen.visibleTranscript());
    }

    @Test
    void resetClearsRetainedHistoryAndScrollOffset() {
        TuiScreen screen = new TuiScreen(10);
        screen.setTranscript(historyLines(1, 20));
        screen.scrollUp(5);

        screen.reset();

        assertEquals(0, screen.retainedLineCount());
        assertEquals(0, screen.linesBelow());
        assertEquals(List.of(), screen.visibleTranscript());
    }

    @Test
    void visibleTranscriptReturnsOnlyViewportTailByDefault() {
        TuiScreen screen = new TuiScreen(2);

        screen.setTranscript(List.of("a", "b"));
        screen.setTranscript(List.of("a", "b", "c"));

        assertEquals(List.of("b", "c"), screen.visibleTranscript());
        assertEquals(0, screen.linesBelow());
    }

    @Test
    void scrollUpShowsOlderTranscriptAndNewTranscriptPreservesOffset() {
        TuiScreen screen = new TuiScreen(2);
        screen.setTranscript(List.of("a", "b", "c", "d"));
        screen.scrollUp(1);

        assertEquals(List.of("b", "c"), screen.visibleTranscript());
        assertEquals(1, screen.linesBelow());

        screen.setTranscript(List.of("a", "b", "c", "d", "e"));

        assertEquals(List.of("b", "c"), screen.visibleTranscript());
        assertEquals(2, screen.linesBelow());

        screen.scrollDown(2);

        assertEquals(List.of("d", "e"), screen.visibleTranscript());
        assertEquals(0, screen.linesBelow());
    }

    @Test
    void pageScrollUsesViewportHeightMinusOne() {
        TuiScreen screen = new TuiScreen(4);
        screen.setTranscript(List.of("a", "b", "c", "d", "e", "f", "g", "h"));

        screen.scrollPageUp();

        assertEquals(List.of("b", "c", "d", "e"), screen.visibleTranscript());
        assertEquals(3, screen.linesBelow());

        screen.scrollPageDown();

        assertEquals(List.of("e", "f", "g", "h"), screen.visibleTranscript());
        assertEquals(0, screen.linesBelow());
    }

    private List<String> historyLines(int first, int last) {
        return IntStream.rangeClosed(first, last)
            .mapToObj(index -> "line-" + index)
            .toList();
    }
}
