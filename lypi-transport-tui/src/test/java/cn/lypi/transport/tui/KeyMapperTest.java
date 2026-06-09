package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KeyMapperTest {
    @Test
    void mapsControlAndAltEditingKeys() {
        KeyMapper mapper = new KeyMapper();

        assertEquals(TerminalKey.CTRL_O, mapper.map("\u000f").orElseThrow());
        assertEquals(TerminalKey.TAB, mapper.map("\t").orElseThrow());
        assertEquals(TerminalKey.ALT_BACKSPACE, mapper.map("\033\u007f").orElseThrow());
        assertEquals(TerminalKey.ALT_DELETE, mapper.map("\033[3;3~").orElseThrow());
    }

    @Test
    void mapsModifiedEnterAndWordMovementSequences() {
        KeyMapper mapper = new KeyMapper();

        assertEquals(TerminalKey.MODIFIED_ENTER, mapper.map("\033[13;5u").orElseThrow());
        assertEquals(TerminalKey.WORD_LEFT, mapper.map("\033[1;5D").orElseThrow());
        assertEquals(TerminalKey.WORD_RIGHT, mapper.map("\033[1;5C").orElseThrow());
        assertEquals(TerminalKey.LEFT, mapper.map("\033OD").orElseThrow());
        assertEquals(TerminalKey.RIGHT, mapper.map("\033[C").orElseThrow());
        assertEquals(TerminalKey.UP, mapper.map("\033[A").orElseThrow());
        assertEquals(TerminalKey.DOWN, mapper.map("\033OB").orElseThrow());
    }

    @Test
    void mapsLineEditingAndYankKeys() {
        KeyMapper mapper = new KeyMapper();

        assertEquals(TerminalKey.CTRL_U, mapper.map("\u0015").orElseThrow());
        assertEquals(TerminalKey.CTRL_Y, mapper.map("\u0019").orElseThrow());
        assertEquals(TerminalKey.CTRL_Z, mapper.map("\u001a").orElseThrow());
        assertEquals(TerminalKey.ALT_Y, mapper.map("\033y").orElseThrow());
    }

    @Test
    void filtersTerminalProtocolResponsesAndReleaseRepeatEvents() {
        KeyMapper mapper = new KeyMapper();

        assertTrue(mapper.map("\033[?u").isEmpty());
        assertTrue(mapper.map("\033[27;7;65u").isEmpty());
        assertTrue(mapper.map("\033[65;129u").isEmpty());
    }
}
