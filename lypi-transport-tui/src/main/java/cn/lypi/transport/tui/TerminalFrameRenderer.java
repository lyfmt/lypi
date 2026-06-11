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
    private static final IntConsumer NOOP_RENDERED_ROWS = rows -> {
    };

    private final TerminalIo io;
    private final IntConsumer renderedRows;
    private List<String> previousLines = List.of();
    private int previousWidth;
    private int previousHeight;
    private int maxLinesRendered;
    private int previousViewportTop;
    private int previousTranscriptLineCount;
    private int hardwareCursorRow;

    TerminalFrameRenderer(TerminalIo io) {
        this(io, NOOP_RENDERED_ROWS);
    }

    TerminalFrameRenderer(TerminalIo io, IntConsumer renderedRows) {
        this.io = io;
        this.renderedRows = renderedRows == null ? NOOP_RENDERED_ROWS : renderedRows;
    }

    void render(List<String> lines) throws IOException {
        render(TuiRenderFrame.transcriptOnly(lines));
    }

    void render(TuiRenderFrame renderFrame) throws IOException {
        CursorFrame frame = stripCursor(renderFrame.lines());
        List<String> newLines = frame.lines();
        int chromeLineCount = renderFrame.chromeLineCount();
        int width = io.width();
        int height = io.height();
        boolean widthChanged = previousWidth != 0 && previousWidth != width;
        boolean heightChanged = previousHeight != 0 && previousHeight != height;
        int viewportTop = viewportTopFor(newLines, height);

        if (previousLines.isEmpty() && !widthChanged && !heightChanged) {
            writeFullFrame(newLines, frame.cursor(), false, viewportTop, height);
            updateState(newLines, width, height, viewportTop, physicalBottomRow(newLines, viewportTop, height), renderFrame.transcriptLineCount());
            return;
        }

        if (widthChanged || heightChanged) {
            logFullRedraw("terminal size changed");
            writeFullFrame(newLines, frame.cursor(), true, viewportTop, height);
            updateState(newLines, width, height, viewportTop, physicalBottomRow(newLines, viewportTop, height), renderFrame.transcriptLineCount());
            return;
        }

        if (newLines.size() < previousLines.size()) {
            viewportTop = Math.max(0, newLines.size() - height);
            writeShrinkPatch(newLines, frame.cursor(), viewportTop, height);
            updateState(newLines, width, height, viewportTop, physicalBottomRow(newLines, viewportTop, height), renderFrame.transcriptLineCount());
            io.flush();
            return;
        }

        int firstChanged = firstChangedLine(newLines);
        if (firstChanged < 0) {
            moveCursor(frame.cursor(), previousViewportTop, height);
            updateState(newLines, width, height, previousViewportTop, hardwareCursorRow, renderFrame.transcriptLineCount());
            return;
        }

        int previousContentViewportTop = Math.max(0, previousLines.size() - height);
        if (firstChanged < previousContentViewportTop) {
            logFullRedraw("first changed line above previous viewport");
            writeFullFrame(newLines, frame.cursor(), true, viewportTop, height);
            updateState(newLines, width, height, viewportTop, physicalBottomRow(newLines, viewportTop, height), renderFrame.transcriptLineCount());
            return;
        }

        boolean appendOnly = newLines.size() > previousLines.size()
            && firstChanged == previousLines.size()
            && viewportTop == previousViewportTop;
        if (appendOnly) {
            StringBuilder buffer = new StringBuilder();
            for (int i = firstChanged; i < newLines.size(); i++) {
                buffer.append("\n").append(newLines.get(i));
            }
            io.write(buffer.toString());
            hardwareCursorRow = physicalBottomRow(newLines, viewportTop, height);
            moveCursor(frame.cursor(), viewportTop, height);
            updateState(newLines, width, height, viewportTop, hardwareCursorRow, renderFrame.transcriptLineCount());
            io.flush();
            return;
        }

        boolean transcriptGrew = renderFrame.transcriptLineCount() > previousTranscriptLineCount;
        if (transcriptGrew && viewportTop > previousViewportTop && newLines.size() > previousLines.size()) {
            writeFlowingTail(newLines, frame.cursor(), firstChanged, previousViewportTop, viewportTop, height, chromeLineCount);
            updateState(newLines, width, height, viewportTop, hardwareCursorRow, renderFrame.transcriptLineCount());
            io.flush();
            return;
        }

        if (viewportTop != previousViewportTop) {
            writeShrinkPatch(newLines, frame.cursor(), viewportTop, height);
            updateState(newLines, width, height, viewportTop, physicalBottomRow(newLines, viewportTop, height), renderFrame.transcriptLineCount());
            io.flush();
            return;
        }

        writePatch(newLines, frame.cursor(), firstChanged, lastChangedLine(newLines), previousViewportTop, height);
        updateState(newLines, width, height, previousViewportTop, hardwareCursorRow, renderFrame.transcriptLineCount());
        io.flush();
    }

    private void writeFullFrame(
        List<String> lines,
        java.util.Optional<CursorPosition> cursor,
        boolean clear,
        int viewportTop,
        int height
    ) throws IOException {
        if (clear) {
            io.write(SYNC_START);
            io.write(FULL_CLEAR);
        }
        io.write(String.join("\n", visibleLines(lines, viewportTop, height)));
        hardwareCursorRow = physicalBottomRow(lines, viewportTop, height);
        moveCursor(cursor, viewportTop, height);
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
        int height
    ) throws IOException {
        io.write(SYNC_START);
        for (int i = firstChanged; i <= Math.min(lastChanged, lines.size() - 1); i++) {
            if (!visibleLogicalRow(i + 1, viewportTop, height)) {
                continue;
            }
            int physicalRow = physicalRow(i + 1, viewportTop, height);
            io.write("\033[" + physicalRow + ";1H");
            io.write("\033[2K");
            io.write(lines.get(i));
            hardwareCursorRow = physicalRow;
        }
        moveCursor(cursor, viewportTop, height);
        io.write(SYNC_END);
    }

    private void writeShrinkPatch(
        List<String> lines,
        java.util.Optional<CursorPosition> cursor,
        int viewportTop,
        int height
    ) throws IOException {
        io.write(SYNC_START);
        List<String> visible = visibleLines(lines, viewportTop, height);
        for (int row = 0; row < Math.max(previousHeight, height); row++) {
            int physicalRow = row + 1;
            if (physicalRow > Math.max(1, height)) {
                break;
            }
            io.write("\033[" + physicalRow + ";1H");
            io.write("\033[2K");
            if (row < visible.size()) {
                io.write(visible.get(row));
                hardwareCursorRow = physicalRow;
            }
        }
        moveCursor(cursor, viewportTop, height);
        io.write(SYNC_END);
    }

    private void writeFlowingTail(
        List<String> lines,
        java.util.Optional<CursorPosition> cursor,
        int firstChanged,
        int previousViewportTop,
        int viewportTop,
        int height,
        int chromeLineCount
    ) throws IOException {
        io.write(SYNC_START);
        int firstVisibleChange = Math.max(firstChanged, previousViewportTop);
        int firstChromeLine = Math.max(0, lines.size() - chromeLineCount);
        int firstVisibleChromeLine = Math.max(firstChromeLine, viewportTop);
        int flowingEnd = chromeLineCount == 0 ? lines.size() : Math.min(lines.size(), firstChromeLine);
        int startRow = physicalRow(firstVisibleChange + 1, previousViewportTop, height);
        if (chromeLineCount == 0) {
            io.write("\033[" + startRow + ";1H");
            for (int i = firstVisibleChange; i < flowingEnd; i++) {
                if (i > firstVisibleChange) {
                    io.write("\r\n");
                }
                io.write("\033[2K");
                io.write(lines.get(i));
                hardwareCursorRow = physicalRow(i + 1, viewportTop, height);
            }
        } else {
            boolean bottomContainsTranscript = false;
            for (int i = firstVisibleChange; i < flowingEnd; i++) {
                int physicalRow = physicalRow(i + 1, previousViewportTop, height);
                io.write("\033[" + physicalRow + ";1H");
                io.write("\033[2K");
                io.write(lines.get(i));
                hardwareCursorRow = physicalRow;
                bottomContainsTranscript = physicalRow == Math.max(1, height);
            }
            int scrollDelta = Math.max(0, viewportTop - previousViewportTop);
            for (int scroll = 0; scroll < scrollDelta; scroll++) {
                io.write("\033[" + Math.max(1, height) + ";1H");
                if (!bottomContainsTranscript) {
                    io.write("\033[2K");
                }
                io.write("\r\n");
                hardwareCursorRow = Math.max(1, height);
                bottomContainsTranscript = false;
            }
            for (int i = firstVisibleChromeLine; i < lines.size(); i++) {
                if (!visibleLogicalRow(i + 1, viewportTop, height)) {
                    continue;
                }
                int physicalRow = physicalRow(i + 1, viewportTop, height);
                io.write("\033[" + physicalRow + ";1H");
                io.write("\033[2K");
                io.write(lines.get(i));
                hardwareCursorRow = physicalRow;
            }
        }
        moveCursor(cursor, viewportTop, height);
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
        if (cursor.isEmpty()) {
            return;
        }
        CursorPosition position = cursor.orElseThrow();
        int physicalRow = physicalRow(position.row(), viewportTop, height);
        io.write("\033[" + physicalRow + ";" + position.column() + "H");
        hardwareCursorRow = physicalRow;
    }

    private void updateState(
        List<String> lines,
        int width,
        int height,
        int viewportTop,
        int currentCursorRow,
        int transcriptLineCount
    ) {
        previousLines = List.copyOf(lines);
        previousWidth = width;
        previousHeight = height;
        maxLinesRendered = Math.max(maxLinesRendered, lines.size());
        previousViewportTop = Math.max(0, viewportTop);
        previousTranscriptLineCount = Math.max(0, transcriptLineCount);
        hardwareCursorRow = Math.max(1, currentCursorRow);
        renderedRows.accept(physicalBottomRow(lines, previousViewportTop, height));
    }

    private int viewportTopFor(List<String> lines, int height) {
        return Math.max(0, Math.max(maxLinesRendered, lines.size()) - height);
    }

    private int physicalBottomRow(List<String> lines, int viewportTop, int height) {
        return physicalRow(Math.max(1, lines.size()), viewportTop, height);
    }

    private int physicalRow(int logicalRow, int viewportTop, int height) {
        int physicalRow = logicalRow - viewportTop;
        return Math.max(1, Math.min(Math.max(1, height), physicalRow));
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
