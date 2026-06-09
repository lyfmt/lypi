package cn.lypi.transport.tui;

record TuiLayout(int width, int height) {
    TuiLayout {
        if (width <= 0) {
            throw new IllegalArgumentException("width must be positive");
        }
        if (height <= 1) {
            throw new IllegalArgumentException("height must be greater than 1");
        }
    }

    int transcriptHeight() {
        return Math.max(1, height - 2);
    }
}
