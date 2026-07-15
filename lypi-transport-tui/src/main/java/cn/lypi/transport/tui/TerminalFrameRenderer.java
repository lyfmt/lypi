package cn.lypi.transport.tui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class TerminalFrameRenderer {
    static final String CURSOR_MARKER = "|CURSOR|";
    private static final String SYNC_START = "\033[?2026h";
    private static final String SYNC_END = "\033[?2026l";
    private static final String FULL_CLEAR = "\033[2J\033[H";
    private static final String CLEAR_LINE = "\033[2K";
    private static final TerminalLine EMPTY_LINE = new TerminalLine("");

    private final TerminalIo io;
    private List<TerminalLine> previousLines = List.of();
    private int previousWidth;
    private int previousHeight;
    private boolean rendered;

    TerminalFrameRenderer(TerminalIo io) {
        this.io = io;
    }

    void render(List<String> lines) throws IOException {
        render(TuiRenderFrame.fromTextLines(lines));
    }

    void render(TuiRenderFrame renderFrame) throws IOException {
        int width = io.width();
        int height = io.height();
        if (height > 0 && renderFrame.terminalLines().size() > height) {
            throw new IllegalArgumentException("frame height exceeds terminal height");
        }

        CursorFrame frame = stripCursor(renderFrame.terminalLines());
        boolean firstFrame = !rendered;
        boolean resized = !firstFrame && (previousWidth != width || previousHeight != height);
        if (firstFrame || resized) {
            writeFullFrame(frame);
        } else {
            writeDiff(frame);
        }
        previousLines = List.copyOf(frame.lines());
        previousWidth = width;
        previousHeight = height;
        rendered = true;
    }

    private void writeFullFrame(CursorFrame frame) throws IOException {
        io.write(SYNC_START);
        io.write(FULL_CLEAR);
        for (int row = 0; row < frame.lines().size(); row++) {
            moveTo(row + 1, 1);
            writeLine(frame.lines().get(row));
        }
        moveCursor(frame.cursor());
        io.write(SYNC_END);
        io.flush();
    }

    private void writeDiff(CursorFrame frame) throws IOException {
        int firstChanged = firstChangedLine(frame.lines());
        int lastChanged = lastChangedLine(frame.lines());
        if (firstChanged >= 0) {
            io.write(SYNC_START);
            for (int row = firstChanged; row <= lastChanged; row++) {
                TerminalLine previous = lineAt(previousLines, row);
                TerminalLine current = lineAt(frame.lines(), row);
                if (previous.equals(current)) {
                    continue;
                }
                moveTo(row + 1, 1);
                io.write(CLEAR_LINE);
                if (row < frame.lines().size()) {
                    writeLine(current);
                }
            }
            moveCursor(frame.cursor());
            io.write(SYNC_END);
            io.flush();
            return;
        }
        if (frame.cursor().isPresent()) {
            moveCursor(frame.cursor());
            io.flush();
        }
    }

    private int firstChangedLine(List<TerminalLine> lines) {
        int maximum = Math.max(previousLines.size(), lines.size());
        for (int row = 0; row < maximum; row++) {
            if (!lineAt(previousLines, row).equals(lineAt(lines, row))) {
                return row;
            }
        }
        return -1;
    }

    private int lastChangedLine(List<TerminalLine> lines) {
        int maximum = Math.max(previousLines.size(), lines.size());
        for (int row = maximum - 1; row >= 0; row--) {
            if (!lineAt(previousLines, row).equals(lineAt(lines, row))) {
                return row;
            }
        }
        return -1;
    }

    private TerminalLine lineAt(List<TerminalLine> lines, int row) {
        return row < lines.size() ? lines.get(row) : EMPTY_LINE;
    }

    private void moveCursor(Optional<CursorPosition> cursor) throws IOException {
        if (cursor.isPresent()) {
            CursorPosition position = cursor.orElseThrow();
            moveTo(position.row(), position.column());
        }
    }

    private void moveTo(int row, int column) throws IOException {
        io.write("\033[" + row + ";" + column + "H");
    }

    private void writeLine(TerminalLine line) throws IOException {
        int width = io.width();
        io.write(width > 0 ? AnsiWidth.truncate(line.text(), width) : line.text());
    }

    private CursorFrame stripCursor(List<TerminalLine> lines) {
        List<TerminalLine> stripped = new ArrayList<>(lines.size());
        CursorPosition cursor = null;
        for (int row = 0; row < lines.size(); row++) {
            TerminalLine line = lines.get(row);
            String text = line.text();
            int marker = text.indexOf(CURSOR_MARKER);
            if (marker < 0) {
                stripped.add(line);
                continue;
            }
            String before = text.substring(0, marker);
            String after = text.substring(marker + CURSOR_MARKER.length());
            stripped.add(new TerminalLine(before + after));
            cursor = new CursorPosition(row + 1, AnsiWidth.displayWidth(before) + 1);
        }
        return new CursorFrame(List.copyOf(stripped), Optional.ofNullable(cursor));
    }

    private record CursorFrame(List<TerminalLine> lines, Optional<CursorPosition> cursor) {
    }

    private record CursorPosition(int row, int column) {
    }
}
