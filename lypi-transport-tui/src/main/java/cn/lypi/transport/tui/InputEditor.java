package cn.lypi.transport.tui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

final class InputEditor {
    private final StringBuilder text = new StringBuilder();
    private final Deque<EditorSnapshot> undo = new ArrayDeque<>();
    private final List<String> killRing = new ArrayList<>();
    private final HistoryRing history = new HistoryRing();
    private int cursor;
    private int killCursor = -1;
    private int lastYankLength;
    private int yankIndex = -1;

    String text() {
        return text.toString();
    }

    int cursor() {
        return cursor;
    }

    void insert(String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        saveUndo();
        text.insert(cursor, value);
        cursor += value.length();
        clearYankState();
        history.resetNavigation(text());
    }

    void insertNewline() {
        insert("\n");
    }

    void insertPaste(String value) {
        String paste = value == null ? "" : value;
        saveUndo();
        text.insert(cursor, paste);
        cursor += paste.length();
        clearYankState();
        history.resetNavigation(text());
    }

    void moveLeft() {
        cursor = Math.max(0, cursor - 1);
        clearYankState();
    }

    void moveRight() {
        cursor = Math.min(text.length(), cursor + 1);
        clearYankState();
    }

    void moveWordLeft() {
        cursor = previousWordStart(cursor);
        clearYankState();
    }

    void moveWordRight() {
        cursor = nextWordEnd(cursor);
        clearYankState();
    }

    void deletePreviousWord() {
        if (cursor == 0) {
            return;
        }
        int start = previousWordStart(cursor);
        kill(start, cursor);
    }

    void deleteNextWord() {
        if (cursor >= text.length()) {
            return;
        }
        int end = nextWordEnd(cursor);
        kill(cursor, end);
    }

    void deleteLineBeforeCursor() {
        int start = text.lastIndexOf("\n", Math.max(0, cursor - 1)) + 1;
        kill(start, cursor);
    }

    void yank() {
        if (killRing.isEmpty()) {
            return;
        }
        yankIndex = killRing.size() - 1;
        insertYank(killRing.get(yankIndex));
    }

    void yankPop() {
        if (yankIndex < 0 || lastYankLength <= 0) {
            return;
        }
        saveUndo();
        int start = Math.max(0, cursor - lastYankLength);
        text.delete(start, cursor);
        cursor = start;
        yankIndex = Math.floorMod(yankIndex - 1, killRing.size());
        String value = killRing.get(yankIndex);
        text.insert(cursor, value);
        cursor += value.length();
        lastYankLength = value.length();
        killCursor = cursor;
        history.resetNavigation(text());
    }

    void undo() {
        if (undo.isEmpty()) {
            return;
        }
        EditorSnapshot snapshot = undo.pop();
        text.setLength(0);
        text.append(snapshot.text());
        cursor = snapshot.cursor();
        clearYankState();
        history.resetNavigation(text());
    }

    void clear() {
        saveUndo();
        text.setLength(0);
        cursor = 0;
        clearYankState();
        history.resetNavigation("");
    }

    void acceptHistoryEntry() {
        history.add(text());
    }

    void previousHistory() {
        history.previous(text()).ifPresent(this::replaceDraftWithoutUndo);
    }

    void nextHistory() {
        history.next().ifPresent(this::replaceDraftWithoutUndo);
    }

    private void kill(int start, int end) {
        if (start >= end) {
            return;
        }
        saveUndo();
        String killed = text.substring(start, end).stripTrailing();
        text.delete(start, end);
        cursor = start;
        killRing.add(killed);
        killCursor = cursor;
        lastYankLength = 0;
        yankIndex = -1;
        history.resetNavigation(text());
    }

    private void insertYank(String value) {
        saveUndo();
        text.insert(cursor, value);
        cursor += value.length();
        lastYankLength = value.length();
        killCursor = cursor;
        history.resetNavigation(text());
    }

    private int previousWordStart(int from) {
        int index = Math.max(0, from);
        while (index > 0 && !isWordChar(text.charAt(index - 1))) {
            index--;
        }
        while (index > 0 && isWordChar(text.charAt(index - 1))) {
            index--;
        }
        return index;
    }

    private int nextWordEnd(int from) {
        int index = Math.min(text.length(), from);
        while (index < text.length() && !isWordChar(text.charAt(index))) {
            index++;
        }
        while (index < text.length() && isWordChar(text.charAt(index))) {
            index++;
        }
        return index;
    }

    private boolean isWordChar(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '/' || value == '-' || value == '.';
    }

    private void saveUndo() {
        undo.push(new EditorSnapshot(text(), cursor));
    }

    private void replaceDraftWithoutUndo(String value) {
        text.setLength(0);
        text.append(value);
        cursor = text.length();
        clearYankState();
    }

    private void clearYankState() {
        if (killCursor != cursor) {
            lastYankLength = 0;
            yankIndex = -1;
            killCursor = -1;
        }
    }

    private record EditorSnapshot(String text, int cursor) {
    }
}
