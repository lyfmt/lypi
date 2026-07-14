package cn.lypi.transport.tui;

import java.util.List;

record TuiRenderFrame(List<TerminalLine> terminalLines, int chromeLineCount) {
    TuiRenderFrame {
        terminalLines = List.copyOf(terminalLines);
        chromeLineCount = Math.max(0, Math.min(chromeLineCount, terminalLines.size()));
    }

    static TuiRenderFrame fromTextLines(List<String> lines, int chromeLineCount) {
        return new TuiRenderFrame(toTerminalLines(lines), chromeLineCount);
    }

    static TuiRenderFrame transcriptOnly(List<String> lines) {
        return fromTextLines(lines, 0);
    }

    List<String> lines() {
        return terminalLines.stream().map(TerminalLine::text).toList();
    }

    int transcriptLineCount() {
        return terminalLines.size() - chromeLineCount;
    }

    private static List<TerminalLine> toTerminalLines(List<String> lines) {
        return lines.stream().map(TerminalLine::new).toList();
    }
}
