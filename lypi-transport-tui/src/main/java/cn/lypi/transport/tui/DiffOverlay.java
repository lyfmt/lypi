package cn.lypi.transport.tui;

import cn.lypi.contracts.tui.DiffView;
import cn.lypi.contracts.tui.GitDiffFileView;
import cn.lypi.contracts.tui.GitDiffStatus;
import java.util.ArrayList;
import java.util.List;

final class DiffOverlay {
    private final DiffView view;

    DiffOverlay(DiffView view) {
        this.view = view;
    }

    List<String> lines() {
        List<String> lines = new ArrayList<>();
        lines.add("diff: " + nullToEmpty(view.summary()));
        for (GitDiffFileView file : view.files()) {
            lines.add(statusSymbol(file.status()) + " " + file.path());
        }
        if (!view.patch().isBlank()) {
            lines.add("");
            lines.addAll(view.patch().lines().toList());
            if (view.truncated()) {
                lines.add("[diff truncated]");
            }
        }
        return lines;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String statusSymbol(GitDiffStatus status) {
        return switch (status) {
            case MODIFIED -> "M";
            case ADDED -> "A";
            case DELETED -> "D";
            case RENAMED -> "R";
            case COPIED -> "C";
            case UNTRACKED -> "?";
        };
    }
}
