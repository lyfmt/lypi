package cn.lypi.transport.tui;

record FileMentionCandidate(String path, boolean writable) {
    FileMentionCandidate {
        path = path == null ? "" : path;
    }
}
