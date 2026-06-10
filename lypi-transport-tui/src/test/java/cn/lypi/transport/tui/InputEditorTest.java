package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class InputEditorTest {
    @Test
    void supportsMultilineInsertAndCursorMovement() {
        InputEditor editor = new InputEditor();

        editor.insert("hello");
        editor.insertNewline();
        editor.insert("world");
        editor.moveLeft();
        editor.insert("!");

        assertEquals("hello\nworl!d", editor.text());
        assertEquals(11, editor.cursor());
    }

    @Test
    void deletesWordsAndLineBeforeCursor() {
        InputEditor editor = new InputEditor();
        editor.insert("alpha beta/gamma");

        editor.deletePreviousWord();

        assertEquals("alpha ", editor.text());

        editor.insert("beta");
        editor.deleteLineBeforeCursor();

        assertEquals("", editor.text());
        assertEquals(0, editor.cursor());
    }

    @Test
    void deletesPreviousCharacterBeforeCursor() {
        InputEditor editor = new InputEditor();
        editor.insert("abcd");
        editor.moveLeft();

        editor.deletePreviousCharacter();

        assertEquals("abd", editor.text());
        assertEquals(2, editor.cursor());
    }

    @Test
    void undoYankAndYankPopUseKillRing() {
        InputEditor editor = new InputEditor();
        editor.insert("alpha beta gamma");
        editor.deletePreviousWord();
        editor.deletePreviousWord();

        editor.yank();
        assertEquals("alpha beta", editor.text());

        editor.yankPop();
        assertEquals("alpha gamma", editor.text());

        editor.undo();
        assertEquals("alpha beta", editor.text());

        editor.undo();
        assertEquals("alpha ", editor.text());
    }

    @Test
    void historyNavigationKeepsCurrentDraft() {
        InputEditor editor = new InputEditor();
        editor.insert("first");
        editor.acceptHistoryEntry();
        editor.clear();
        editor.insert("second");
        editor.acceptHistoryEntry();
        editor.clear();
        editor.insert("draft");

        editor.previousHistory();
        assertEquals("second", editor.text());
        editor.previousHistory();
        assertEquals("first", editor.text());
        editor.nextHistory();
        assertEquals("second", editor.text());
        editor.nextHistory();
        assertEquals("draft", editor.text());
    }

    @Test
    void bracketedPasteIsAtomicAndCompressesLargePasteMarker() {
        InputEditor editor = new InputEditor();

        editor.insertPaste("a".repeat(128));

        assertEquals("a".repeat(128), editor.text());
        editor.undo();
        assertEquals("", editor.text());
    }
}
