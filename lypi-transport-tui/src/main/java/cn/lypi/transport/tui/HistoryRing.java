package cn.lypi.transport.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class HistoryRing {
    private final List<String> entries = new ArrayList<>();
    private int cursor = -1;
    private String draft = "";

    void add(String entry) {
        if (entry == null || entry.isBlank()) {
            resetNavigation("");
            return;
        }
        entries.add(entry);
        resetNavigation("");
    }

    void resetNavigation(String currentDraft) {
        cursor = entries.size();
        draft = currentDraft == null ? "" : currentDraft;
    }

    Optional<String> previous(String currentDraft) {
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        if (cursor == entries.size()) {
            draft = currentDraft == null ? "" : currentDraft;
        }
        cursor = Math.max(0, cursor - 1);
        return Optional.of(entries.get(cursor));
    }

    Optional<String> next() {
        if (entries.isEmpty() || cursor >= entries.size()) {
            return Optional.empty();
        }
        cursor++;
        if (cursor >= entries.size()) {
            return Optional.of(draft);
        }
        return Optional.of(entries.get(cursor));
    }

    boolean navigating() {
        return cursor >= 0 && cursor < entries.size();
    }
}
