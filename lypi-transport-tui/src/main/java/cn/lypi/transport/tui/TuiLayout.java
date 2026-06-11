package cn.lypi.transport.tui;

record TuiLayout(int width, int height) {
    private static final int STATUS_BAR_HEIGHT = 1;
    private static final int INPUT_BORDER_HEIGHT = 2;
    private static final int MIN_INPUT_CONTENT_HEIGHT = 1;

    TuiLayout {
        if (width <= 0) {
            throw new IllegalArgumentException("width must be positive");
        }
        if (height <= 1) {
            throw new IllegalArgumentException("height must be greater than 1");
        }
    }

    int transcriptHeight() {
        return transcriptHeight(MIN_INPUT_CONTENT_HEIGHT + INPUT_BORDER_HEIGHT);
    }

    int transcriptHeight(int inputBlockHeight) {
        int boundedInputBlockHeight = Math.min(maxInputBlockHeight(), Math.max(1, inputBlockHeight));
        return Math.max(0, height - STATUS_BAR_HEIGHT - boundedInputBlockHeight);
    }

    int maxInputBlockHeight() {
        return Math.max(1, height - STATUS_BAR_HEIGHT);
    }

    int maxInputContentHeight() {
        int maxInputBlockHeight = maxInputBlockHeight();
        if (maxInputBlockHeight <= INPUT_BORDER_HEIGHT) {
            return MIN_INPUT_CONTENT_HEIGHT;
        }
        return Math.max(MIN_INPUT_CONTENT_HEIGHT, maxInputBlockHeight - INPUT_BORDER_HEIGHT);
    }
}
