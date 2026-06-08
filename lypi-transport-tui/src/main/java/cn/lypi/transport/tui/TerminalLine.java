package cn.lypi.transport.tui;

record TerminalLine(String text) {
    TerminalLine {
        text = text == null ? "" : text;
    }

    int width() {
        return AnsiWidth.displayWidth(text);
    }
}
