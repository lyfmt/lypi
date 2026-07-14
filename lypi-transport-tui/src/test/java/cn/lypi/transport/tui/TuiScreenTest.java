package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class TuiScreenTest {
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
}
