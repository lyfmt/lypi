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
        return allocate(MIN_INPUT_CONTENT_HEIGHT + INPUT_BORDER_HEIGHT, 0, true).transcriptHeight();
    }

    int transcriptHeight(int inputBlockHeight) {
        return allocate(inputBlockHeight, 0, true).transcriptHeight();
    }

    int maxInputBlockHeight() {
        return allocate(Integer.MAX_VALUE, 0, false).inputHeight();
    }

    int maxInputContentHeight() {
        int maxInputBlockHeight = maxInputBlockHeight();
        if (maxInputBlockHeight <= INPUT_BORDER_HEIGHT) {
            return MIN_INPUT_CONTENT_HEIGHT;
        }
        return Math.max(MIN_INPUT_CONTENT_HEIGHT, maxInputBlockHeight - INPUT_BORDER_HEIGHT);
    }

    TuiRegionLayout allocate(int desiredInputHeight, int desiredOverlayHeight, boolean hasTranscript) {
        int inputHeight = 1;
        int transcriptHeight = 0;
        int overlayHeight = 0;
        int remainingHeight = height - STATUS_BAR_HEIGHT - inputHeight;

        if (hasTranscript && remainingHeight > 0) {
            transcriptHeight = 1;
            remainingHeight--;
        }

        int boundedOverlayHeight = Math.max(0, desiredOverlayHeight);
        overlayHeight = Math.min(boundedOverlayHeight, remainingHeight);
        remainingHeight -= overlayHeight;

        int boundedInputHeight = Math.max(1, desiredInputHeight);
        int additionalInputHeight = Math.min(boundedInputHeight - inputHeight, remainingHeight);
        inputHeight += additionalInputHeight;
        remainingHeight -= additionalInputHeight;

        transcriptHeight += remainingHeight;
        return new TuiRegionLayout(transcriptHeight, inputHeight, overlayHeight, STATUS_BAR_HEIGHT);
    }
}
