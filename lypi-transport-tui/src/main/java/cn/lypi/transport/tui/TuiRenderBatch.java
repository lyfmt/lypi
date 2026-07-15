package cn.lypi.transport.tui;

import java.util.List;
import java.util.Objects;

record TuiRenderBatch(List<TerminalLine> historyLines, TuiRenderFrame surface) {
    TuiRenderBatch {
        historyLines = List.copyOf(historyLines);
        surface = Objects.requireNonNull(surface, "surface");
    }
}
