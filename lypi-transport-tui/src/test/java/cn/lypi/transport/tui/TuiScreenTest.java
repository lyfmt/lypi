package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class TuiScreenTest {
    @Test
    void visibleTranscriptReturnsFullTranscriptForTerminalScrollback() {
        TuiScreen screen = new TuiScreen(2);

        screen.setTranscript(List.of("a", "b"));
        screen.setTranscript(List.of("a", "b", "c"));

        assertEquals(List.of("a", "b", "c"), screen.visibleTranscript());
        assertEquals(0, screen.linesBelow());
    }

    @Test
    void applicationScrollMethodsDoNotHideTranscriptLines() {
        TuiScreen screen = new TuiScreen(2);
        screen.setTranscript(List.of("a", "b", "c", "d"));
        screen.scrollUp(1);

        assertEquals(List.of("a", "b", "c", "d"), screen.visibleTranscript());

        screen.setTranscript(List.of("a", "b", "c", "d", "e"));
        screen.scrollDown(1);

        assertEquals(List.of("a", "b", "c", "d", "e"), screen.visibleTranscript());
        assertEquals(0, screen.linesBelow());
    }
}
