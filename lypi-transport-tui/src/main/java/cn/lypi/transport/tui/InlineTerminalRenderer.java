package cn.lypi.transport.tui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class InlineTerminalRenderer {
    private static final String SYNC_START = "\033[?2026h";
    private static final String SYNC_END = "\033[?2026l";
    private static final String RESET_SCROLL_REGION = "\033[r";
    private static final String CLEAR_LINE = "\033[2K";
    private static final String REVERSE_INDEX = "\033M";
    private static final TerminalLine EMPTY_LINE = new TerminalLine("");

    private final TerminalIo io;
    private final boolean startupBannerEnabled;
    private InlineViewport viewport;
    private InlineViewport renderedViewport;
    private List<TerminalLine> previousSurface = List.of();
    private Optional<SurfaceCursor> previousCursor = Optional.empty();
    private Optional<TerminalPosition> resizeCursorPosition = Optional.empty();
    private boolean geometryDirty;
    private boolean startupBannerCommitted;
    private boolean finished;

    InlineTerminalRenderer(TerminalIo io, InlineViewport viewport) {
        this(io, viewport, false);
    }

    static InlineTerminalRenderer withStartupBanner(TerminalIo io, InlineViewport viewport) {
        return new InlineTerminalRenderer(io, viewport, true);
    }

    private InlineTerminalRenderer(TerminalIo io, InlineViewport viewport, boolean startupBannerEnabled) {
        this.io = java.util.Objects.requireNonNull(io, "io");
        this.viewport = java.util.Objects.requireNonNull(viewport, "viewport");
        this.startupBannerEnabled = startupBannerEnabled;
    }

    void render(TuiRenderBatch batch) throws IOException {
        if (finished) {
            throw new IllegalStateException("inline terminal renderer is finished");
        }
        SurfaceFrame surface = stripCursor(batch.surface());
        if (surface.lines().isEmpty()) {
            throw new IllegalArgumentException("mutable surface must contain at least one line");
        }
        InlineViewport nextViewport = viewport.withSurfaceHeight(surface.lines().size());
        boolean commitStartupBanner = startupBannerEnabled && !startupBannerCommitted;
        List<TerminalLine> history = prepareHistory(
            pendingHistory(batch, nextViewport, commitStartupBanner),
            nextViewport.width()
        );

        if (history.isEmpty()
            && renderedViewport != null
            && !geometryDirty
            && sameGeometry(renderedViewport, nextViewport)
            && previousSurface.equals(surface.lines())) {
            if (!previousCursor.equals(surface.cursor())) {
                moveCursor(surface.cursor(), nextViewport);
                io.flush();
            }
            viewport = nextViewport;
            renderedViewport = nextViewport;
            previousCursor = surface.cursor();
            return;
        }

        InlineViewport finalViewport = writeRenderTransaction(
            history,
            surface,
            nextViewport
        );

        viewport = finalViewport;
        renderedViewport = finalViewport;
        previousSurface = surface.lines();
        previousCursor = surface.cursor();
        resizeCursorPosition = Optional.empty();
        geometryDirty = false;
        startupBannerCommitted = startupBannerCommitted || commitStartupBanner;
    }

    private List<TerminalLine> pendingHistory(
        TuiRenderBatch batch,
        InlineViewport nextViewport,
        boolean includeStartupBanner
    ) {
        if (!includeStartupBanner) {
            return batch.historyLines();
        }
        int availableRows = Math.max(
            0,
            nextViewport.terminalHeight() - nextViewport.top() - nextViewport.surfaceHeight()
        );
        List<TerminalLine> combined = new ArrayList<>(
            TuiStartupBanner.render(nextViewport.width(), availableRows)
        );
        combined.addAll(batch.historyLines());
        return List.copyOf(combined);
    }

    void resize(int width, int height) {
        resize(width, height, Optional.empty());
    }

    void resize(int width, int height, Optional<TerminalPosition> cursorPosition) {
        viewport = viewport.resize(width, height);
        resizeCursorPosition = cursorPosition == null ? Optional.empty() : cursorPosition;
        geometryDirty = true;
    }

    void finish() throws IOException {
        if (finished) {
            return;
        }
        finished = true;
        InlineViewport current = renderedViewport == null ? viewport : renderedViewport;
        io.write(SYNC_START);
        IOException failure = null;
        try {
            io.write(RESET_SCROLL_REGION);
            clearRows(current.top(), current.surfaceHeight(), current.terminalHeight());
            moveTo(current.top() + 1, 1);
        } catch (IOException exception) {
            failure = exception;
        }
        try {
            io.write(SYNC_END);
        } catch (IOException exception) {
            failure = combine(failure, exception);
        }
        try {
            io.flush();
        } catch (IOException exception) {
            failure = combine(failure, exception);
        }
        previousSurface = List.of();
        previousCursor = Optional.empty();
        resizeCursorPosition = Optional.empty();
        if (failure != null) {
            throw failure;
        }
    }

    private InlineViewport writeRenderTransaction(
        List<TerminalLine> history,
        SurfaceFrame surface,
        InlineViewport nextViewport
    ) throws IOException {
        io.write(SYNC_START);
        InlineViewport finalViewport = nextViewport;
        IOException failure = null;
        try {
            boolean geometryChanged = renderedViewport == null
                || geometryDirty
                || !sameGeometry(renderedViewport, nextViewport);
            if (geometryChanged && renderedViewport != null) {
                InlineViewport physicalPrevious = physicalViewportAfterResize(renderedViewport, nextViewport);
                scrollCommittedRowsForUpwardViewport(physicalPrevious, nextViewport);
                clearSurfaceUnion(physicalPrevious, nextViewport);
            }
            boolean linearHistoryInsertion = requiresLinearHistoryInsertion(history, nextViewport);
            finalViewport = insertHistory(history, nextViewport);
            if (geometryChanged
                || renderedViewport == null
                || linearHistoryInsertion
                || !sameGeometry(nextViewport, finalViewport)) {
                drawFullSurface(surface.lines(), finalViewport);
            } else {
                drawSurfaceDiff(surface.lines(), finalViewport);
            }
            moveCursor(surface.cursor(), finalViewport);
        } catch (IOException exception) {
            failure = exception;
        }
        try {
            io.write(SYNC_END);
        } catch (IOException exception) {
            failure = combine(failure, exception);
        }
        try {
            io.flush();
        } catch (IOException exception) {
            failure = combine(failure, exception);
        }
        if (failure != null) {
            throw failure;
        }
        return finalViewport;
    }

    private InlineViewport physicalViewportAfterResize(
        InlineViewport previous,
        InlineViewport next
    ) {
        int nextHeight = next.terminalHeight();
        int cursorRow = reflowedCursorRow(next.width());
        int physicalTop = resizeCursorPosition
            .map(position -> Math.max(0, position.row() - cursorRow))
            .orElseGet(() -> {
                int previousCursorRow = previous.top() + cursorRow;
                int terminalScroll = Math.max(0, previousCursorRow - (nextHeight - 1));
                return Math.max(0, previous.top() - terminalScroll);
            });
        physicalTop = Math.min(physicalTop, nextHeight - 1);
        int physicalHeight = Math.min(reflowedSurfaceHeight(next.width()), nextHeight - physicalTop);
        return new InlineViewport(physicalTop, physicalHeight, next.width(), nextHeight);
    }

    private int reflowedCursorRow(int width) {
        SurfaceCursor cursor = previousCursor.orElseGet(() -> new SurfaceCursor(
            previousSurface.size() - 1,
            AnsiWidth.displayWidth(previousSurface.getLast().text())
        ));
        int row = 0;
        for (int index = 0; index < cursor.row(); index++) {
            row += reflowedLineHeight(previousSurface.get(index), width);
        }
        return row + cursor.column() / width;
    }

    private int reflowedSurfaceHeight(int width) {
        return previousSurface.stream()
            .mapToInt(line -> reflowedLineHeight(line, width))
            .sum();
    }

    private int reflowedLineHeight(TerminalLine line, int width) {
        int displayWidth = AnsiWidth.displayWidth(line.text());
        return Math.max(1, (displayWidth + width - 1) / width);
    }

    private void scrollCommittedRowsForUpwardViewport(
        InlineViewport previous,
        InlineViewport next
    ) throws IOException {
        if (next.top() >= previous.top()) {
            return;
        }
        int regionBottom = Math.min(previous.top(), next.terminalHeight());
        if (regionBottom < 1) {
            return;
        }
        setScrollRegion(1, regionBottom);
        moveTo(regionBottom, 1);
        for (int index = 0; index < previous.top() - next.top(); index++) {
            io.write("\r\n");
        }
        io.write(RESET_SCROLL_REGION);
    }

    private InlineViewport insertHistory(List<TerminalLine> history, InlineViewport current) throws IOException {
        if (history.isEmpty()) {
            return current;
        }
        int spaceBelow = current.terminalHeight() - current.top() - current.surfaceHeight();
        int scrollAmount = Math.min(history.size(), Math.max(0, spaceBelow));
        int prospectiveTop = current.top() + scrollAmount;
        if (prospectiveTop < 2) {
            return insertHistoryLinearly(history, current);
        }

        int cursorTop = Math.max(0, current.top() - 1);
        InlineViewport shifted = current;
        if (scrollAmount > 0) {
            setScrollRegion(current.top() + 1, current.terminalHeight());
            moveTo(current.top() + 1, 1);
            for (int index = 0; index < scrollAmount; index++) {
                io.write(REVERSE_INDEX);
            }
            io.write(RESET_SCROLL_REGION);
            shifted = new InlineViewport(
                prospectiveTop,
                current.surfaceHeight(),
                current.width(),
                current.terminalHeight()
            );
        }

        setScrollRegion(1, shifted.top());
        moveTo(cursorTop + 1, 1);
        for (TerminalLine line : history) {
            io.write("\r\n");
            writeLine(line, shifted.width());
        }
        io.write(RESET_SCROLL_REGION);
        return shifted;
    }

    private boolean requiresLinearHistoryInsertion(List<TerminalLine> history, InlineViewport current) {
        if (history.isEmpty()) {
            return false;
        }
        int spaceBelow = current.terminalHeight() - current.top() - current.surfaceHeight();
        int scrollAmount = Math.min(history.size(), Math.max(0, spaceBelow));
        return current.top() + scrollAmount < 2;
    }

    private InlineViewport insertHistoryLinearly(List<TerminalLine> history, InlineViewport current) throws IOException {
        clearRows(current.top(), current.surfaceHeight(), current.terminalHeight());
        moveTo(current.top() + 1, 1);
        for (int index = 0; index < history.size(); index++) {
            if (index > 0) {
                io.write("\r\n");
            }
            io.write(CLEAR_LINE);
            writeLine(history.get(index), current.width());
        }
        for (int index = 0; index < current.surfaceHeight(); index++) {
            io.write("\r\n");
            io.write(CLEAR_LINE);
        }
        int nextTop = Math.min(
            current.terminalHeight() - current.surfaceHeight(),
            current.top() + history.size()
        );
        return new InlineViewport(nextTop, current.surfaceHeight(), current.width(), current.terminalHeight());
    }

    private void drawFullSurface(List<TerminalLine> lines, InlineViewport current) throws IOException {
        for (int row = 0; row < lines.size(); row++) {
            moveTo(current.top() + row + 1, 1);
            io.write(CLEAR_LINE);
            writeLine(lines.get(row), current.width());
        }
    }

    private void drawSurfaceDiff(List<TerminalLine> lines, InlineViewport current) throws IOException {
        int rows = Math.max(previousSurface.size(), lines.size());
        for (int row = 0; row < rows; row++) {
            TerminalLine previous = lineAt(previousSurface, row);
            TerminalLine next = lineAt(lines, row);
            if (previous.equals(next)) {
                continue;
            }
            moveTo(current.top() + row + 1, 1);
            io.write(CLEAR_LINE);
            if (row < lines.size()) {
                writeLine(next, current.width());
            }
        }
    }

    private void clearSurfaceUnion(InlineViewport previous, InlineViewport next) throws IOException {
        int top = Math.min(previous.top(), next.top());
        int bottom = Math.max(
            previous.top() + previous.surfaceHeight(),
            next.top() + next.surfaceHeight()
        );
        clearRows(top, bottom - top, next.terminalHeight());
    }

    private void clearRows(int top, int height, int terminalHeight) throws IOException {
        int first = Math.max(0, top);
        int end = Math.min(terminalHeight, top + height);
        for (int row = first; row < end; row++) {
            moveTo(row + 1, 1);
            io.write(CLEAR_LINE);
        }
    }

    private SurfaceFrame stripCursor(TuiRenderFrame frame) {
        List<TerminalLine> lines = new ArrayList<>(frame.terminalLines().size());
        SurfaceCursor cursor = null;
        int width = viewport.width();
        for (int row = 0; row < frame.terminalLines().size(); row++) {
            String text = frame.terminalLines().get(row).text();
            int marker = text.indexOf(TuiRenderFrame.CURSOR_MARKER);
            if (marker < 0) {
                lines.add(new TerminalLine(AnsiWidth.truncate(text, width)));
                continue;
            }
            String before = text.substring(0, marker);
            String after = text.substring(marker + TuiRenderFrame.CURSOR_MARKER.length());
            lines.add(new TerminalLine(AnsiWidth.truncate(before + after, width)));
            int column = Math.min(Math.max(0, width - 1), AnsiWidth.displayWidth(before));
            cursor = new SurfaceCursor(row, column);
        }
        return new SurfaceFrame(List.copyOf(lines), Optional.ofNullable(cursor));
    }

    private List<TerminalLine> prepareHistory(List<TerminalLine> history, int width) {
        List<TerminalLine> lines = new ArrayList<>(history.size());
        for (TerminalLine line : history) {
            if (line.text().contains(TuiRenderFrame.CURSOR_MARKER)) {
                throw new IllegalArgumentException("history line must not contain cursor marker");
            }
            lines.add(new TerminalLine(AnsiWidth.truncate(line.text(), width)));
        }
        return List.copyOf(lines);
    }

    private void moveCursor(Optional<SurfaceCursor> cursor, InlineViewport current) throws IOException {
        if (cursor.isEmpty()) {
            return;
        }
        SurfaceCursor position = cursor.orElseThrow();
        moveTo(current.top() + position.row() + 1, position.column() + 1);
    }

    private void writeLine(TerminalLine line, int width) throws IOException {
        io.write(AnsiWidth.truncate(line.text(), width));
    }

    private void setScrollRegion(int top, int bottom) throws IOException {
        if (top < 1 || bottom < top) {
            throw new IllegalArgumentException("invalid scroll region " + top + ";" + bottom);
        }
        io.write("\033[" + top + ";" + bottom + "r");
    }

    private void moveTo(int row, int column) throws IOException {
        io.write("\033[" + row + ";" + column + "H");
    }

    private TerminalLine lineAt(List<TerminalLine> lines, int row) {
        return row < lines.size() ? lines.get(row) : EMPTY_LINE;
    }

    private boolean sameGeometry(InlineViewport left, InlineViewport right) {
        return left.top() == right.top()
            && left.surfaceHeight() == right.surfaceHeight()
            && left.width() == right.width()
            && left.terminalHeight() == right.terminalHeight();
    }

    private IOException combine(IOException first, IOException next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    private record SurfaceFrame(List<TerminalLine> lines, Optional<SurfaceCursor> cursor) {
    }

    private record SurfaceCursor(int row, int column) {
    }
}
