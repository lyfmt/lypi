package cn.lypi.tool.builtin;

import java.util.List;

record RipgrepSearchResult(List<String> lines, boolean isError, String message) {
    RipgrepSearchResult {
        lines = lines == null ? List.of() : List.copyOf(lines);
        message = message == null ? "" : message;
    }

    static RipgrepSearchResult success(List<String> lines) {
        return new RipgrepSearchResult(lines, false, "");
    }

    static RipgrepSearchResult error(String message) {
        return new RipgrepSearchResult(List.of(), true, message);
    }
}
