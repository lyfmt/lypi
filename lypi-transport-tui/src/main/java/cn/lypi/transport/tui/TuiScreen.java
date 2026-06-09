package cn.lypi.transport.tui;

import java.util.ArrayList;
import java.util.List;

final class TuiScreen {
    private final int viewportHeight;
    private List<String> transcript = List.of();
    private int viewportStart;

    TuiScreen(int viewportHeight) {
        if (viewportHeight <= 0) {
            throw new IllegalArgumentException("viewportHeight must be positive");
        }
        this.viewportHeight = viewportHeight;
    }

    void setTranscript(List<String> transcript) {
        List<String> nextTranscript = List.copyOf(transcript);
        boolean shouldFollowBottom = viewportStart + viewportHeight >= this.transcript.size();
        this.transcript = nextTranscript;
        if (shouldFollowBottom) {
            viewportStart = Math.max(0, nextTranscript.size() - viewportHeight);
            return;
        }
        viewportStart = Math.min(viewportStart, maxViewportStart());
    }

    void scrollUp(int lines) {
        viewportStart = Math.max(0, viewportStart - Math.max(0, lines));
    }

    void scrollDown(int lines) {
        viewportStart = Math.min(maxViewportStart(), viewportStart + Math.max(0, lines));
    }

    List<String> visibleTranscript() {
        int end = Math.min(transcript.size(), viewportStart + viewportHeight);
        return new ArrayList<>(transcript.subList(viewportStart, end));
    }

    int linesBelow() {
        return Math.max(0, transcript.size() - viewportStart - viewportHeight);
    }

    private int maxViewportStart() {
        return Math.max(0, transcript.size() - viewportHeight);
    }
}
