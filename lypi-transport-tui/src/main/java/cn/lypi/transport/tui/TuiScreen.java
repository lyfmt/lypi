package cn.lypi.transport.tui;

import java.util.List;

final class TuiScreen {
    private int viewportHeight;
    private List<String> transcript = List.of();
    private int linesBelow;

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
        int previousTranscriptSize = this.transcript.size();
        boolean followingTail = linesBelow == 0;
        this.transcript = List.copyOf(transcript);
        if (followingTail) {
            linesBelow = 0;
            return;
        }
        int appendedLines = Math.max(0, this.transcript.size() - previousTranscriptSize);
        linesBelow = Math.min(maxLinesBelow(), linesBelow + appendedLines);
    }

    void scrollUp(int lines) {
        linesBelow = Math.min(maxLinesBelow(), linesBelow + Math.max(0, lines));
    }

    void scrollDown(int lines) {
        linesBelow = Math.max(0, linesBelow - Math.max(0, lines));
    }

    List<String> visibleTranscript() {
        if (transcript.isEmpty() || viewportHeight == 0) {
            return List.of();
        }
        int end = Math.max(0, transcript.size() - linesBelow);
        int start = Math.max(0, end - viewportHeight);
        return List.copyOf(transcript.subList(start, end));
    }

    int linesBelow() {
        return linesBelow;
    }

    private int maxLinesBelow() {
        return Math.max(0, transcript.size() - viewportHeight);
    }
}
