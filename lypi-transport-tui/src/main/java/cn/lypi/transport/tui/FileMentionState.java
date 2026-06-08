package cn.lypi.transport.tui;

record FileMentionState(String draft, int cursor, String filter) {
    FileMentionState {
        draft = draft == null ? "" : draft;
        filter = filter == null ? "" : filter;
    }
}
