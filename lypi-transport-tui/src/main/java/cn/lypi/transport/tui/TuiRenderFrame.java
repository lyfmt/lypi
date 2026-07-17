package cn.lypi.transport.tui;

import java.util.List;

record TuiRenderFrame(List<TerminalLine> terminalLines) {
    static final String CURSOR_MARKER = "|CURSOR|";

    TuiRenderFrame {
        terminalLines = List.copyOf(terminalLines);
    }

    static TuiRenderFrame fromTextLines(List<String> lines) {
        return new TuiRenderFrame(toTerminalLines(lines));
    }

    List<String> lines() {
        return terminalLines.stream().map(TerminalLine::text).toList();
    }

    private static List<TerminalLine> toTerminalLines(List<String> lines) {
        return lines.stream().map(TerminalLine::new).toList();
    }
}
