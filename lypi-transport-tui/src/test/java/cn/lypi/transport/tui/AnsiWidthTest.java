package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AnsiWidthTest {
    @Test
    void ansiOscApcAndDcsEscapesAreZeroWidth() {
        String value = "ab\033[31m红\033[0m\033]0;title\007\033_Pignored\033\\\033^ignored\033\\";

        assertEquals(4, AnsiWidth.displayWidth(value));
    }

    @Test
    void combiningMarksAndZwjDoNotAdvanceWidth() {
        assertEquals(1, AnsiWidth.displayWidth("e\u0301"));
        assertEquals(2, AnsiWidth.displayWidth("👨‍💻"));
    }

    @Test
    void truncateKeepsAnsiSequencesAndRespectsCellWidth() {
        assertEquals("\033[31m红\033[0m…", AnsiWidth.truncate("\033[31m红色\033[0m", 3));
    }
}
