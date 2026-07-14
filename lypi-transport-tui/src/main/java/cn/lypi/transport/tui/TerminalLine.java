package cn.lypi.transport.tui;

record TerminalLine(String text) {
    TerminalLine {
        text = text == null ? "" : text;
        if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("terminal line must contain exactly one physical line");
        }
    }

    int width() {
        return AnsiWidth.displayWidth(text);
    }
}
