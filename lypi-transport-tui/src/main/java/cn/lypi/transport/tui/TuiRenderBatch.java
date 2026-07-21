package cn.lypi.transport.tui;

import java.util.List;
import java.util.Objects;

record TuiRenderBatch(
    List<TerminalLine> historyLines,
    TuiRenderFrame surface,
    TuiRenderIntent intent
) {
    TuiRenderBatch(List<TerminalLine> historyLines, TuiRenderFrame surface) {
        this(historyLines, surface, TuiRenderIntent.UPDATE);
    }

    TuiRenderBatch {
        historyLines = List.copyOf(Objects.requireNonNull(historyLines, "historyLines"));
        surface = Objects.requireNonNull(surface, "surface");
        intent = Objects.requireNonNull(intent, "intent");
    }
}
