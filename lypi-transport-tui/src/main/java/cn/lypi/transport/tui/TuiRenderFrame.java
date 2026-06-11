package cn.lypi.transport.tui;

import java.util.List;

record TuiRenderFrame(List<String> lines, int chromeLineCount) {
    TuiRenderFrame {
        lines = List.copyOf(lines);
        chromeLineCount = Math.max(0, Math.min(chromeLineCount, lines.size()));
    }

    static TuiRenderFrame transcriptOnly(List<String> lines) {
        return new TuiRenderFrame(lines, 0);
    }

    int transcriptLineCount() {
        return lines.size() - chromeLineCount;
    }
}
