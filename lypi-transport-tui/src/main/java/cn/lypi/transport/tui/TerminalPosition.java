package cn.lypi.transport.tui;

record TerminalPosition(int column, int row) {
    TerminalPosition {
        if (column < 0 || row < 0) {
            throw new IllegalArgumentException("terminal position must be non-negative");
        }
    }
}
