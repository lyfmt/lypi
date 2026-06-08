package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KeyMapperTest {
    @Test
    void mapsControlAndAltEditingKeys() {
        KeyMapper mapper = new KeyMapper();

        assertEquals(TerminalKey.CTRL_O, mapper.map("\u000f").orElseThrow());
        assertEquals(TerminalKey.ALT_BACKSPACE, mapper.map("\033\u007f").orElseThrow());
        assertEquals(TerminalKey.ALT_DELETE, mapper.map("\033[3;3~").orElseThrow());
    }

    @Test
    void mapsModifiedEnterAndWordMovementSequences() {
        KeyMapper mapper = new KeyMapper();

        assertEquals(TerminalKey.MODIFIED_ENTER, mapper.map("\033[13;5u").orElseThrow());
        assertEquals(TerminalKey.WORD_LEFT, mapper.map("\033[1;5D").orElseThrow());
        assertEquals(TerminalKey.WORD_RIGHT, mapper.map("\033OC").orElseThrow());
    }

    @Test
    void filtersTerminalProtocolResponsesAndReleaseRepeatEvents() {
        KeyMapper mapper = new KeyMapper();

        assertTrue(mapper.map("\033[?u").isEmpty());
        assertTrue(mapper.map("\033[27;7;65u").isEmpty());
        assertTrue(mapper.map("\033[65;129u").isEmpty());
    }
}
