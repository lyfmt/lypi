package cn.lypi.transport.tui;

import cn.lypi.contracts.tui.DiffView;
import java.util.List;

final class DiffOverlay {
    private final DiffView view;

    DiffOverlay(DiffView view) {
        this.view = view;
    }

    List<String> lines() {
        return List.of(
            "diff: " + nullToEmpty(view.path()),
            "ref: " + nullToEmpty(view.diffRef())
        );
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
