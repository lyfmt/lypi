package cn.lypi.transport.tui;

record TuiRegionLayout(
    int transcriptHeight,
    int inputHeight,
    int overlayHeight,
    int statusHeight
) {
    TuiRegionLayout {
        if (transcriptHeight < 0 || inputHeight < 0 || overlayHeight < 0 || statusHeight < 0) {
            throw new IllegalArgumentException("region heights must be non-negative");
        }
    }

    int totalHeight() {
        return transcriptHeight + inputHeight + overlayHeight + statusHeight;
    }
}
