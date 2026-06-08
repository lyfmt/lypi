package cn.lypi.transport.tui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class TerminalFrameRenderer {
    static final String CURSOR_MARKER = "|CURSOR|";
    private static final String SYNC_START = "\033[?2026h";
    private static final String SYNC_END = "\033[?2026l";
    private static final String HOME_AND_CLEAR = "\033[H\033[J";

    private final TerminalIo io;

    TerminalFrameRenderer(TerminalIo io) {
        this.io = io;
    }

    void render(List<String> lines) throws IOException {
        CursorFrame frame = stripCursor(lines);
        io.write(SYNC_START);
        io.write(HOME_AND_CLEAR);
        io.write(String.join("\n", frame.lines()));
        if (frame.cursor().isPresent()) {
            CursorPosition cursor = frame.cursor().orElseThrow();
            io.write("\033[" + cursor.row() + ";" + cursor.column() + "H");
        }
        io.write(SYNC_END);
        io.flush();
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
