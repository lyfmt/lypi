package cn.lypi.transport.tui;

record TuiContentLayout(int historyHeight, int separatorHeight, int liveHeight) {
    TuiContentLayout {
        if (historyHeight < 0 || separatorHeight < 0 || liveHeight < 0) {
            throw new IllegalArgumentException("content region heights must be non-negative");
        }
    }

    static TuiContentLayout allocate(int height, boolean hasHistory, int desiredLiveHeight) {
        if (height < 0) {
            throw new IllegalArgumentException("height must be non-negative");
        }
        if (desiredLiveHeight < 0) {
            throw new IllegalArgumentException("desiredLiveHeight must be non-negative");
        }
        if (height == 0) {
            return new TuiContentLayout(0, 0, 0);
        }

        boolean hasLive = desiredLiveHeight > 0;
        if (!hasHistory) {
            return hasLive
                ? new TuiContentLayout(0, 0, height)
                : new TuiContentLayout(height, 0, 0);
        }
        if (!hasLive) {
            return new TuiContentLayout(height, 0, 0);
        }
        if (height == 1) {
            return new TuiContentLayout(0, 0, 1);
        }
        if (height == 2) {
            return new TuiContentLayout(1, 0, 1);
        }

        int separatorHeight = 1;
        int availableContentHeight = height - separatorHeight;
        int liveHeight = Math.min(desiredLiveHeight, availableContentHeight / 2);
        int historyHeight = availableContentHeight - liveHeight;
        return new TuiContentLayout(historyHeight, separatorHeight, liveHeight);
    }

    int totalHeight() {
        return historyHeight + separatorHeight + liveHeight;
    }
}
