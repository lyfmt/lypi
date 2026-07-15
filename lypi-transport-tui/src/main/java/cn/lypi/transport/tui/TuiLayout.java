package cn.lypi.transport.tui;

record TuiLayout(int width, int height) {
    private static final int STATUS_BAR_HEIGHT = 1;

    TuiLayout {
        if (width <= 0) {
            throw new IllegalArgumentException("width must be positive");
        }
        if (height <= 1) {
            throw new IllegalArgumentException("height must be greater than 1");
        }
    }

    int maxSurfaceHeight() {
        return Math.max(1, height - 1);
    }

    TuiRegionLayout allocateSurface(int desiredLiveHeight, int desiredInputHeight, int desiredOverlayHeight) {
        int budget = maxSurfaceHeight();
        int inputHeight = 1;
        int statusHeight = budget > 1 ? STATUS_BAR_HEIGHT : 0;
        int remainingHeight = budget - inputHeight - statusHeight;

        int boundedOverlayHeight = Math.max(0, desiredOverlayHeight);
        int overlayHeight = Math.min(boundedOverlayHeight, remainingHeight);
        remainingHeight -= overlayHeight;

        int boundedInputHeight = Math.max(1, desiredInputHeight);
        int additionalInputHeight = Math.min(boundedInputHeight - inputHeight, remainingHeight);
        inputHeight += additionalInputHeight;
        remainingHeight -= additionalInputHeight;

        int liveHeight = Math.min(Math.max(0, desiredLiveHeight), remainingHeight);
        return new TuiRegionLayout(liveHeight, inputHeight, overlayHeight, statusHeight);
    }

}
