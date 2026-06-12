package cn.lypi.transport.tui;

import java.util.List;

record TuiRenderFrame(List<String> lines) {
    TuiRenderFrame {
        lines = List.copyOf(lines);
    }

    static TuiRenderFrame of(List<String> lines) {
        return new TuiRenderFrame(lines);
    }
}
