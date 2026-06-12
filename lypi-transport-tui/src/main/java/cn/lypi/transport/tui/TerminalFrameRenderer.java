package cn.lypi.transport.tui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

final class TerminalFrameRenderer {
    static final String CURSOR_MARKER = "|CURSOR|";
    private static final String SYNC_START = "\033[?2026h";
    private static final String SYNC_END = "\033[?2026l";
    private static final String FULL_CLEAR = "\033[2J\033[H";
    private static final String ANSI_RESET = "\033[0m";
    private static final String WELCOME_PRIMARY = "\033[38;5;81m";
    private static final String WELCOME_ACCENT = "\033[38;5;213m";
    private static final String WELCOME_DIM = "\033[38;5;244m";
    private static final String WELCOME_BOLD = "\033[1m";
    private static final IntConsumer NOOP_RENDERED_ROWS = rows -> {
    };

    private final TerminalIo io;
    private final IntConsumer renderedRows;
    private final boolean startupPaddingEnabled;
    private List<String> previousLines = List.of();
    private int previousWidth;
    private int previousHeight;
    private int previousTopRow = 1;
    private int previousAreaHeight;
    private int maxLinesRendered;
    private int previousViewportTop;
    private int hardwareCursorRow;
    private int startupPaddingLineCount = -1;

    TerminalFrameRenderer(TerminalIo io) {
        this(io, NOOP_RENDERED_ROWS, false);
    }

    TerminalFrameRenderer(TerminalIo io, IntConsumer renderedRows) {
        this(io, renderedRows, false);
    }

    static TerminalFrameRenderer withStartupPadding(TerminalIo io, IntConsumer renderedRows) {
        return new TerminalFrameRenderer(io, renderedRows, true);
    }

    private TerminalFrameRenderer(TerminalIo io, IntConsumer renderedRows, boolean startupPaddingEnabled) {
        this.io = io;
        this.renderedRows = renderedRows == null ? NOOP_RENDERED_ROWS : renderedRows;
        this.startupPaddingEnabled = startupPaddingEnabled;
    }

    void render(List<String> lines) throws IOException {
        render(TuiRenderFrame.of(lines));
    }

    void render(TuiRenderFrame renderFrame) throws IOException {
        renderInArea(renderFrame, 1, io.height());
    }

    void renderInArea(TuiRenderFrame renderFrame, int topRow, int areaHeight) throws IOException {
        int width = io.width();
        int height = Math.max(1, areaHeight);
        int boundedTopRow = Math.max(1, Math.min(Math.max(1, io.height()), topRow));
        List<String> rawLines = renderFrame.lines();
        if (startupPaddingEnabled && (startupPaddingLineCount < 0 || previousAreaHeight != 0 && previousAreaHeight != height)) {
            startupPaddingLineCount = Math.max(0, height - rawLines.size());
        }
        CursorFrame frame = stripCursor(withStartupPadding(rawLines));
        List<String> newLines = frame.lines();
        boolean widthChanged = previousWidth != 0 && previousWidth != width;
        boolean heightChanged = previousHeight != 0 && previousHeight != height;
        boolean areaChanged = previousTopRow != boundedTopRow || previousAreaHeight != 0 && previousAreaHeight != height;
        int viewportTop = viewportTopFor(newLines, height);

        if (previousLines.isEmpty() && !widthChanged && !heightChanged && !areaChanged) {
            writeFullFrame(newLines, frame.cursor(), startupPaddingEnabled, viewportTop, height, boundedTopRow);
            updateState(newLines, width, height, boundedTopRow, viewportTop, physicalBottomRow(newLines, viewportTop, height, boundedTopRow));
            return;
        }

        if (widthChanged || heightChanged || areaChanged) {
            logFullRedraw("terminal size changed");
            writeFullFrame(newLines, frame.cursor(), true, viewportTop, height, boundedTopRow);
            updateState(newLines, width, height, boundedTopRow, viewportTop, physicalBottomRow(newLines, viewportTop, height, boundedTopRow));
            return;
        }

        if (newLines.size() < previousLines.size()) {
            viewportTop = Math.max(0, newLines.size() - height);
            writeShrinkPatch(newLines, frame.cursor(), viewportTop, height, boundedTopRow);
            updateState(newLines, width, height, boundedTopRow, viewportTop, physicalBottomRow(newLines, viewportTop, height, boundedTopRow));
            io.flush();
            return;
        }

        int firstChanged = firstChangedLine(newLines);
        if (firstChanged < 0) {
            moveCursor(frame.cursor(), previousViewportTop, height, boundedTopRow);
            updateState(newLines, width, height, boundedTopRow, previousViewportTop, hardwareCursorRow);
            return;
        }

        int previousContentViewportTop = Math.max(0, previousLines.size() - height);
        if (firstChanged < previousContentViewportTop) {
            logFullRedraw("first changed line above previous viewport");
            writeFullFrame(newLines, frame.cursor(), true, viewportTop, height, boundedTopRow);
            updateState(newLines, width, height, boundedTopRow, viewportTop, physicalBottomRow(newLines, viewportTop, height, boundedTopRow));
            return;
        }

        if (viewportTop != previousViewportTop) {
            writeShrinkPatch(newLines, frame.cursor(), viewportTop, height, boundedTopRow);
            updateState(newLines, width, height, boundedTopRow, viewportTop, physicalBottomRow(newLines, viewportTop, height, boundedTopRow));
            io.flush();
            return;
        }

        writePatch(newLines, frame.cursor(), firstChanged, lastChangedLine(newLines), previousViewportTop, height, boundedTopRow);
        updateState(newLines, width, height, boundedTopRow, previousViewportTop, hardwareCursorRow);
        io.flush();
    }

    void invalidateViewport() {
        previousLines = List.of();
        previousWidth = 0;
        previousHeight = 0;
        previousTopRow = 1;
        previousAreaHeight = 0;
        previousViewportTop = 0;
        hardwareCursorRow = 0;
    }

    private void writeFullFrame(
        List<String> lines,
        java.util.Optional<CursorPosition> cursor,
        boolean clear,
        int viewportTop,
        int height,
        int topRow
    ) throws IOException {
        if (clear) {
            io.write(SYNC_START);
            clearArea(topRow, height);
        }
        writeVisibleRows(visibleLines(lines, viewportTop, height), topRow);
        hardwareCursorRow = physicalBottomRow(lines, viewportTop, height, topRow);
        moveCursor(cursor, viewportTop, height, topRow);
        if (clear) {
            io.write(SYNC_END);
        }
        io.flush();
    }

    private void writePatch(
        List<String> lines,
        java.util.Optional<CursorPosition> cursor,
        int firstChanged,
        int lastChanged,
        int viewportTop,
        int height,
        int topRow
    ) throws IOException {
        io.write(SYNC_START);
        for (int i = firstChanged; i <= Math.min(lastChanged, lines.size() - 1); i++) {
            if (!visibleLogicalRow(i + 1, viewportTop, height)) {
                continue;
            }
            int physicalRow = physicalRow(i + 1, viewportTop, height, topRow);
            io.write("\033[" + physicalRow + ";1H");
            io.write("\033[2K");
            writeLine(lines.get(i));
            hardwareCursorRow = physicalRow;
        }
        moveCursor(cursor, viewportTop, height, topRow);
        io.write(SYNC_END);
    }

    private void writeShrinkPatch(
        List<String> lines,
        java.util.Optional<CursorPosition> cursor,
        int viewportTop,
        int height,
        int topRow
    ) throws IOException {
        io.write(SYNC_START);
        List<String> visible = visibleLines(lines, viewportTop, height);
        for (int row = 0; row < Math.max(previousHeight, height); row++) {
            int physicalRow = topRow + row;
            if (row >= Math.max(1, height)) {
                break;
            }
            io.write("\033[" + physicalRow + ";1H");
            io.write("\033[2K");
            if (row < visible.size()) {
                writeLine(visible.get(row));
                hardwareCursorRow = physicalRow;
            }
        }
        moveCursor(cursor, viewportTop, height, topRow);
        io.write(SYNC_END);
    }

    private int firstChangedLine(List<String> newLines) {
        int max = Math.max(previousLines.size(), newLines.size());
        for (int i = 0; i < max; i++) {
            String previous = i < previousLines.size() ? previousLines.get(i) : "";
            String current = i < newLines.size() ? newLines.get(i) : "";
            if (!previous.equals(current)) {
                return i;
            }
        }
        return -1;
    }

    private int lastChangedLine(List<String> newLines) {
        int max = Math.max(previousLines.size(), newLines.size());
        for (int i = max - 1; i >= 0; i--) {
            String previous = i < previousLines.size() ? previousLines.get(i) : "";
            String current = i < newLines.size() ? newLines.get(i) : "";
            if (!previous.equals(current)) {
                return i;
            }
        }
        return -1;
    }

    private void moveCursor(java.util.Optional<CursorPosition> cursor, int viewportTop, int height) throws IOException {
        moveCursor(cursor, viewportTop, height, 1);
    }

    private void moveCursor(java.util.Optional<CursorPosition> cursor, int viewportTop, int height, int topRow) throws IOException {
        if (cursor.isEmpty()) {
            return;
        }
        CursorPosition position = cursor.orElseThrow();
        int physicalRow = physicalRow(position.row(), viewportTop, height, topRow);
        io.write("\033[" + physicalRow + ";" + position.column() + "H");
        hardwareCursorRow = physicalRow;
    }

    private void updateState(
        List<String> lines,
        int width,
        int height,
        int topRow,
        int viewportTop,
        int currentCursorRow
    ) {
        previousLines = List.copyOf(lines);
        previousWidth = width;
        previousHeight = height;
        previousTopRow = topRow;
        previousAreaHeight = height;
        maxLinesRendered = Math.max(maxLinesRendered, lines.size());
        previousViewportTop = Math.max(0, viewportTop);
        hardwareCursorRow = Math.max(1, currentCursorRow);
        renderedRows.accept(physicalBottomRow(lines, previousViewportTop, height, topRow));
    }

    private int viewportTopFor(List<String> lines, int height) {
        return Math.max(0, lines.size() - height);
    }

    private int physicalBottomRow(List<String> lines, int viewportTop, int height) {
        return physicalBottomRow(lines, viewportTop, height, 1);
    }

    private int physicalBottomRow(List<String> lines, int viewportTop, int height, int topRow) {
        return physicalRow(Math.max(1, lines.size()), viewportTop, height, topRow);
    }

    private int physicalRow(int logicalRow, int viewportTop, int height) {
        return physicalRow(logicalRow, viewportTop, height, 1);
    }

    private int physicalRow(int logicalRow, int viewportTop, int height, int topRow) {
        int physicalRow = topRow + logicalRow - viewportTop - 1;
        int bottomRow = topRow + Math.max(1, height) - 1;
        return Math.max(topRow, Math.min(bottomRow, physicalRow));
    }

    private boolean visibleLogicalRow(int logicalRow, int viewportTop, int height) {
        int physicalRow = logicalRow - viewportTop;
        return physicalRow >= 1 && physicalRow <= Math.max(1, height);
    }

    private List<String> visibleLines(List<String> lines, int viewportTop, int height) {
        if (lines.isEmpty()) {
            return List.of();
        }
        int start = Math.max(0, viewportTop);
        int end = Math.min(lines.size(), start + Math.max(1, height));
        if (start >= end) {
            return List.of();
        }
        return lines.subList(start, end);
    }

    private List<String> withStartupPadding(List<String> lines) {
        if (startupPaddingLineCount <= 0) {
            return lines;
        }
        List<String> padded = new ArrayList<>(startupPaddingLineCount + lines.size());
        padded.addAll(startupWelcomeLines(startupPaddingLineCount, io.width()));
        padded.addAll(lines);
        return padded;
    }

    private List<String> startupWelcomeLines(int lineCount, int width) {
        if (lineCount <= 0) {
            return List.of();
        }
        List<String> content = width >= 46 ? fullWelcomeLines(width) : compactWelcomeLines(width);
        List<String> result = new ArrayList<>(lineCount);
        int topPadding = Math.max(0, (lineCount - content.size()) / 2);
        for (int i = 0; i < topPadding && result.size() < lineCount; i++) {
            result.add("");
        }
        for (String line : content) {
            if (result.size() >= lineCount) {
                break;
            }
            result.add(line);
        }
        while (result.size() < lineCount) {
            result.add("");
        }
        return result;
    }

    private List<String> fullWelcomeLines(int width) {
        return List.of(
            center(WELCOME_DIM + "╭────────────────────────────────────────────────────────╮" + ANSI_RESET, width),
            center(WELCOME_PRIMARY + WELCOME_BOLD + "██╗      ██╗   ██╗      ██████╗  ██╗" + ANSI_RESET, width),
            center(WELCOME_PRIMARY + WELCOME_BOLD + "██║      ╚██╗ ██╔╝      ██╔══██╗ ██║" + ANSI_RESET, width),
            center(WELCOME_ACCENT + WELCOME_BOLD + "██║       ╚████╔╝ █████╗██████╔╝ ██║" + ANSI_RESET, width),
            center(WELCOME_ACCENT + WELCOME_BOLD + "██║        ╚██╔╝  ╚════╝██╔═══╝  ██║" + ANSI_RESET, width),
            center(WELCOME_PRIMARY + WELCOME_BOLD + "███████╗    ██║         ██║      ██║" + ANSI_RESET, width),
            center(WELCOME_DIM + "╰──────────────────────── LY-PI ────────────────────────╯" + ANSI_RESET, width),
            center(WELCOME_ACCENT + "coding agent cockpit" + ANSI_RESET, width)
        );
    }

    private List<String> compactWelcomeLines(int width) {
        return List.of(
            center(WELCOME_PRIMARY + WELCOME_BOLD + "LY-PI" + ANSI_RESET, width),
            center(WELCOME_ACCENT + "coding agent" + ANSI_RESET, width)
        );
    }

    private String center(String line, int width) {
        int lineWidth = AnsiWidth.displayWidth(line);
        if (lineWidth >= width) {
            return line;
        }
        return " ".repeat((width - lineWidth) / 2) + line;
    }

    private void clearArea(int topRow, int height) throws IOException {
        if (topRow == 1 && height >= io.height()) {
            io.write(FULL_CLEAR);
            return;
        }
        for (int row = 0; row < height; row++) {
            io.write("\033[" + (topRow + row) + ";1H");
            io.write("\033[2K");
        }
    }

    private void writeVisibleRows(List<String> lines, int topRow) throws IOException {
        for (int index = 0; index < lines.size(); index++) {
            io.write("\033[" + (topRow + index) + ";1H");
            io.write("\033[2K");
            writeLine(lines.get(index));
        }
    }

    private void writeLine(String line) throws IOException {
        io.write(AnsiWidth.truncate(line, io.width()));
    }

    private void logFullRedraw(String reason) {
        if (!"1".equals(System.getenv("PI_DEBUG_REDRAW"))) {
            return;
        }
        System.err.println("PI_DEBUG_REDRAW full render: " + reason
            + " previousLines=" + previousLines.size()
            + " maxLinesRendered=" + maxLinesRendered
            + " previousViewportTop=" + previousViewportTop);
    }

    private CursorFrame stripCursor(List<String> lines) {
        List<String> stripped = new ArrayList<>();
        CursorPosition cursor = null;
        for (int row = 0; row < lines.size(); row++) {
            String line = lines.get(row);
            int marker = line.indexOf(CURSOR_MARKER);
            if (marker >= 0) {
                String before = line.substring(0, marker);
                String after = line.substring(marker + CURSOR_MARKER.length());
                stripped.add(before + after);
                cursor = new CursorPosition(row + 1, AnsiWidth.displayWidth(before) + 1);
            } else {
                stripped.add(line);
            }
        }
        return new CursorFrame(stripped, java.util.Optional.ofNullable(cursor));
    }

    private record CursorFrame(List<String> lines, java.util.Optional<CursorPosition> cursor) {
    }

    private record CursorPosition(int row, int column) {
    }
}
