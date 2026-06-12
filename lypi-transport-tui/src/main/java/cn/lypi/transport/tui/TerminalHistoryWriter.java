package cn.lypi.transport.tui;

import java.io.IOException;
import java.util.List;

final class TerminalHistoryWriter {
    private final TerminalIo io;

    TerminalHistoryWriter(TerminalIo io) {
        this.io = io;
    }

    TuiViewportArea insertAboveViewport(List<String> lines, TuiViewportArea viewportArea) throws IOException {
        if (viewportArea == null) {
            return null;
        }
        if (lines == null || lines.isEmpty() || viewportArea.topRow() <= 1) {
            return viewportArea;
        }
        TuiViewportArea updatedArea = viewportArea;
        int scrollAmount = 0;
        if (viewportArea.bottomRow() < io.height()) {
            scrollAmount = Math.min(lines.size(), io.height() - viewportArea.bottomRow());
            int scrollTop = viewportArea.topRow();
            io.write("\033[" + scrollTop + ";" + io.height() + "r");
            io.write("\033[" + viewportArea.topRow() + ";1H");
            for (int i = 0; i < scrollAmount; i++) {
                io.write("\033M");
            }
            io.write("\033[r");
            updatedArea = new TuiViewportArea(viewportArea.topRow() + scrollAmount, viewportArea.height());
        }
        int scrollBottom = updatedArea.scrollRegionBottom();
        io.write("\033[1;" + scrollBottom + "r");
        io.write("\033[" + scrollBottom + ";1H");
        for (String line : lines) {
            io.write("\r\n");
            io.write(line == null ? "" : line);
        }
        io.write("\033[r");
        io.flush();
        return updatedArea;
    }
}
