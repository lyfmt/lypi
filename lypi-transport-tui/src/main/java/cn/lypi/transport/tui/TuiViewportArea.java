package cn.lypi.transport.tui;

record TuiViewportArea(int topRow, int height) {
    TuiViewportArea {
        topRow = Math.max(1, topRow);
        height = Math.max(1, height);
    }

    static TuiViewportArea bottomAligned(int screenHeight, int frameLineCount) {
        return bottomAligned(screenHeight, frameLineCount, false);
    }

    static TuiViewportArea bottomAligned(int screenHeight, int frameLineCount, boolean reserveHistoryRegion) {
        int boundedScreenHeight = Math.max(1, screenHeight);
        int maxViewportHeight = reserveHistoryRegion ? Math.max(1, boundedScreenHeight - 1) : boundedScreenHeight;
        int boundedHeight = Math.max(1, Math.min(maxViewportHeight, frameLineCount));
        return new TuiViewportArea(boundedScreenHeight - boundedHeight + 1, boundedHeight);
    }

    static TuiViewportArea fullScreen(int screenHeight) {
        return new TuiViewportArea(1, Math.max(1, screenHeight));
    }

    int bottomRow() {
        return topRow + height - 1;
    }

    int scrollRegionBottom() {
        return topRow - 1;
    }
}
