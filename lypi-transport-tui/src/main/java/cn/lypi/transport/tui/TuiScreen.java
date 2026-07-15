package cn.lypi.transport.tui;

import java.util.List;

final class TuiScreen {
    static final int MAX_RETAINED_HISTORY_LINES = 500;

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
        List<String> previousTranscript = this.transcript;
        boolean followingTail = linesBelow == 0;
        this.transcript = retainTail(transcript);
        if (followingTail) {
            linesBelow = 0;
            return;
        }
        int overlap = maximumSuffixPrefixOverlap(previousTranscript, this.transcript);
        int appendedLines = this.transcript.size() - overlap;
        linesBelow = Math.min(maxLinesBelow(), linesBelow + appendedLines);
    }

    void scrollUp(int lines) {
        linesBelow = Math.min(maxLinesBelow(), linesBelow + Math.max(0, lines));
    }

    void scrollDown(int lines) {
        linesBelow = Math.max(0, linesBelow - Math.max(0, lines));
    }

    void scrollPageUp() {
        scrollUp(pageSize());
    }

    void scrollPageDown() {
        scrollDown(pageSize());
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

    int retainedLineCount() {
        return transcript.size();
    }

    void reset() {
        transcript = List.of();
        linesBelow = 0;
    }

    private List<String> retainTail(List<String> lines) {
        int from = Math.max(0, lines.size() - MAX_RETAINED_HISTORY_LINES);
        return List.copyOf(lines.subList(from, lines.size()));
    }

    private int maximumSuffixPrefixOverlap(List<String> previous, List<String> current) {
        int maximum = Math.min(previous.size(), current.size());
        for (int overlap = maximum; overlap > 0; overlap--) {
            if (previous.subList(previous.size() - overlap, previous.size()).equals(current.subList(0, overlap))) {
                return overlap;
            }
        }
        return 0;
    }

    private int maxLinesBelow() {
        return Math.max(0, transcript.size() - viewportHeight);
    }

    private int pageSize() {
        return Math.max(1, viewportHeight - 1);
    }
}
