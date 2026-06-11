package cn.lypi.transport.tui;

import java.util.List;

final class TuiScreen {
    private int viewportHeight;
    private List<String> transcript = List.of();

    TuiScreen(int viewportHeight) {
        if (viewportHeight <= 0) {
            throw new IllegalArgumentException("viewportHeight must be positive");
        }
        this.viewportHeight = viewportHeight;
    }

    void updateViewportHeight(int viewportHeight) {
        if (viewportHeight < 0) {
            throw new IllegalArgumentException("viewportHeight must be non-negative");
        }
        this.viewportHeight = viewportHeight;
    }

    void setTranscript(List<String> transcript) {
        this.transcript = List.copyOf(transcript);
    }

    void scrollUp(int lines) {
    }

    void scrollDown(int lines) {
    }

    List<String> visibleTranscript() {
        return List.copyOf(transcript);
    }

    int linesBelow() {
        return 0;
    }
}
