package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TerminalLineTest {
    @ParameterizedTest
    @ValueSource(strings = {"one\ntwo", "one\rtwo", "one\r\ntwo"})
    void rejectsTextThatMovesToAnotherPhysicalLine(String text) {
        assertThrows(IllegalArgumentException.class, () -> new TerminalLine(text));
    }

    @Test
    void normalizesNullToEmptyPhysicalLine() {
        assertEquals("", new TerminalLine(null).text());
    }

    @Test
    void ansiStyledTextKeepsItsDisplayWidth() {
        TerminalLine line = new TerminalLine("\033[31mred\033[0m");

        assertEquals(3, line.width());
    }
}
