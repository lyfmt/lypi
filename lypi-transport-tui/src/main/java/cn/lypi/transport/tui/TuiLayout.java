package cn.lypi.transport.tui;

record TuiLayout(int width, int height) {
    private static final int STATUS_BAR_HEIGHT = 1;
    private static final int MIN_INPUT_HEIGHT = 1;
    private static final int MAX_INPUT_HEIGHT = 10;

    TuiLayout {
        if (width <= 0) {
            throw new IllegalArgumentException("width must be positive");
        }
        if (height <= 1) {
            throw new IllegalArgumentException("height must be greater than 1");
        }
    }

    int transcriptHeight() {
        return transcriptHeight(MIN_INPUT_HEIGHT);
    }

    int transcriptHeight(int inputHeight) {
        return Math.max(1, height - STATUS_BAR_HEIGHT - clampedInputHeight(inputHeight));
    }

    int clampedInputHeight(int requestedHeight) {
        int availableForInput = Math.max(MIN_INPUT_HEIGHT, height - STATUS_BAR_HEIGHT - 1);
        return Math.max(MIN_INPUT_HEIGHT, Math.min(Math.min(MAX_INPUT_HEIGHT, availableForInput), requestedHeight));
    }

    int maxInputHeight() {
        return clampedInputHeight(MAX_INPUT_HEIGHT);
    }
}
