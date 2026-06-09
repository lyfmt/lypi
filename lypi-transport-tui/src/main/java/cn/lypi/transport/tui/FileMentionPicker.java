package cn.lypi.transport.tui;

import java.util.List;
import java.util.Optional;

final class FileMentionPicker {
    private final List<FileMentionCandidate> candidates;
    private String draft = "";
    private int cursor;
    private int tokenStart = -1;
    private String filter = "";

    FileMentionPicker(List<FileMentionCandidate> candidates) {
        this.candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    void updateDraft(String draft, int cursor) {
        this.draft = draft == null ? "" : draft;
        this.cursor = Math.max(0, Math.min(cursor, this.draft.length()));
        this.tokenStart = this.draft.lastIndexOf('@', Math.max(0, this.cursor - 1));
        this.filter = tokenStart < 0 ? "" : this.draft.substring(tokenStart + 1, this.cursor);
    }

    List<String> visiblePaths() {
        String normalized = filter.toLowerCase();
        return candidates.stream()
            .map(FileMentionCandidate::path)
            .filter(path -> path.toLowerCase().contains(normalized))
            .toList();
    }

    Optional<FileMentionState> accept() {
        List<String> visible = visiblePaths();
        if (tokenStart < 0 || visible.isEmpty()) {
            return Optional.empty();
        }
        String path = visible.getFirst();
        String replacement = path.contains(" ") ? "@\"" + path + "\"" : "@" + path;
        String nextDraft = draft.substring(0, tokenStart) + replacement + draft.substring(cursor);
        return Optional.of(new FileMentionState(nextDraft, tokenStart + replacement.length(), filter));
    }

    FileMentionState cancel() {
        return new FileMentionState(draft, cursor, filter);
    }
}
