package cn.lypi.transport.tui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

final class InputEditor {
    private static final int INPUT_PREFIX_WIDTH = 2;
    private static final int INPUT_CURSOR_WIDTH = 1;

    private final StringBuilder text = new StringBuilder();
    private final Deque<EditorSnapshot> undo = new ArrayDeque<>();
    private final List<String> killRing = new ArrayList<>();
    private final HistoryRing history = new HistoryRing();
    private int cursor;
    private int preferredColumn = -1;
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
        preferredColumn = -1;
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
        preferredColumn = -1;
        clearYankState();
        history.resetNavigation(text());
    }

    void moveLeft() {
        cursor = Math.max(0, cursor - 1);
        preferredColumn = -1;
        clearYankState();
    }

    void moveRight() {
        cursor = Math.min(text.length(), cursor + 1);
        preferredColumn = -1;
        clearYankState();
    }

    void moveWordLeft() {
        cursor = previousWordStart(cursor);
        preferredColumn = -1;
        clearYankState();
    }

    void moveWordRight() {
        cursor = nextWordEnd(cursor);
        preferredColumn = -1;
        clearYankState();
    }

    void moveUp() {
        moveVertical(-1);
    }

    void moveDown() {
        moveVertical(1);
    }

    void moveVisualUp(int width) {
        moveVisualVertical(width, -1);
    }

    void moveVisualDown(int width) {
        moveVisualVertical(width, 1);
    }

    boolean canMoveUp() {
        return lineStart(cursor) > 0;
    }

    boolean canMoveDown() {
        return lineEnd(cursor) < text.length();
    }

    boolean canMoveVisualUp(int width) {
        return visualLineIndex(width) > 0;
    }

    boolean canMoveVisualDown(int width) {
        List<VisualLine> lines = visualLines(width);
        return visualLineIndex(lines) < lines.size() - 1;
    }

    void deletePreviousCharacter() {
        if (cursor == 0) {
            return;
        }
        saveUndo();
        text.delete(cursor - 1, cursor);
        cursor--;
        preferredColumn = -1;
        clearYankState();
        history.resetNavigation(text());
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
        preferredColumn = -1;
        clearYankState();
        history.resetNavigation(text());
    }

    void clear() {
        saveUndo();
        text.setLength(0);
        cursor = 0;
        preferredColumn = -1;
        clearYankState();
        history.resetNavigation("");
    }

    void replaceFirstToken(String value) {
        String replacement = value == null ? "" : value;
        int end = firstTokenEnd();
        saveUndo();
        text.replace(0, end, replacement);
        cursor = replacement.length();
        preferredColumn = -1;
        clearYankState();
        history.resetNavigation(text());
    }

    void replaceDraft(String value) {
        saveUndo();
        replaceDraftWithoutUndo(value == null ? "" : value);
        history.resetNavigation(text());
    }

    void acceptHistoryEntry() {
        history.add(text());
    }

    void previousHistory() {
        if (!text.isEmpty() && !history.navigating()) {
            return;
        }
        history.previous(text()).ifPresent(this::replaceDraftWithoutUndo);
    }

    void nextHistory() {
        if (!history.navigating()) {
            return;
        }
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
        preferredColumn = -1;
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
        preferredColumn = -1;
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

    private void moveVertical(int delta) {
        int currentStart = lineStart(cursor);
        int currentColumn = preferredColumn >= 0 ? preferredColumn : cursor - currentStart;
        int targetStart;
        if (delta < 0) {
            if (currentStart == 0) {
                return;
            }
            targetStart = lineStart(currentStart - 1);
        } else {
            int currentEnd = lineEnd(cursor);
            if (currentEnd >= text.length()) {
                return;
            }
            targetStart = currentEnd + 1;
        }
        int targetEnd = lineEnd(targetStart);
        cursor = Math.min(targetStart + currentColumn, targetEnd);
        preferredColumn = currentColumn;
        clearYankState();
    }

    private void moveVisualVertical(int width, int delta) {
        List<VisualLine> lines = visualLines(width);
        int currentLine = visualLineIndex(lines);
        int targetLine = currentLine + delta;
        if (targetLine < 0 || targetLine >= lines.size()) {
            return;
        }
        int currentColumn = preferredColumn >= 0 ? preferredColumn : cursorColumn(lines.get(currentLine), cursor);
        VisualLine target = lines.get(targetLine);
        cursor = cursorAtColumn(target, currentColumn);
        preferredColumn = currentColumn;
        clearYankState();
    }

    private int visualLineIndex(int width) {
        return visualLineIndex(visualLines(width));
    }

    private int visualLineIndex(List<VisualLine> lines) {
        for (int index = 0; index < lines.size(); index++) {
            VisualLine line = lines.get(index);
            if (cursor >= line.start() && cursor <= line.end()) {
                return index;
            }
        }
        return Math.max(0, lines.size() - 1);
    }

    private List<VisualLine> visualLines(int width) {
        List<VisualLine> lines = new ArrayList<>();
        int firstContentWidth = Math.max(1, width - INPUT_PREFIX_WIDTH - INPUT_CURSOR_WIDTH);
        int otherContentWidth = Math.max(1, width - INPUT_CURSOR_WIDTH);
        int lineStart = 0;
        int lineWidth = 0;
        boolean firstLine = true;

        for (int index = 0; index < text.length();) {
            int codePoint = text.codePointAt(index);
            if (codePoint == '\n') {
                lines.add(new VisualLine(lineStart, index));
                index += Character.charCount(codePoint);
                lineStart = index;
                lineWidth = 0;
                firstLine = false;
                continue;
            }

            String chunk = new String(Character.toChars(codePoint));
            int chunkWidth = AnsiWidth.displayWidth(chunk);
            int availableWidth = firstLine ? firstContentWidth : otherContentWidth;
            if (lineWidth > 0 && lineWidth + chunkWidth > availableWidth) {
                lines.add(new VisualLine(lineStart, index));
                lineStart = index;
                lineWidth = 0;
                firstLine = false;
                availableWidth = otherContentWidth;
            }
            lineWidth += chunkWidth;
            index += Character.charCount(codePoint);
        }

        lines.add(new VisualLine(lineStart, text.length()));
        return lines;
    }

    private int cursorColumn(VisualLine line, int position) {
        int column = 0;
        int end = Math.min(Math.max(position, line.start()), line.end());
        for (int index = line.start(); index < end;) {
            int codePoint = text.codePointAt(index);
            column += AnsiWidth.displayWidth(new String(Character.toChars(codePoint)));
            index += Character.charCount(codePoint);
        }
        return column;
    }

    private int cursorAtColumn(VisualLine line, int column) {
        int width = 0;
        for (int index = line.start(); index < line.end();) {
            int codePoint = text.codePointAt(index);
            int codePointWidth = AnsiWidth.displayWidth(new String(Character.toChars(codePoint)));
            if (width + codePointWidth > column) {
                return index;
            }
            width += codePointWidth;
            index += Character.charCount(codePoint);
        }
        return line.end();
    }

    private int lineStart(int from) {
        return text.lastIndexOf("\n", Math.max(0, Math.min(from, text.length()) - 1)) + 1;
    }

    private int lineEnd(int from) {
        int newline = text.indexOf("\n", Math.max(0, Math.min(from, text.length())));
        return newline < 0 ? text.length() : newline;
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
        preferredColumn = -1;
        clearYankState();
    }

    private int firstTokenEnd() {
        int index = 0;
        while (index < text.length() && !Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index;
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

    private record VisualLine(int start, int end) {
    }
}
