package cn.lypi.tool.builtin;

import java.util.Locale;

enum GrepOutputMode {
    CONTENT("content"),
    FILES_WITH_MATCHES("files_with_matches"),
    COUNT("count");

    private final String inputValue;

    GrepOutputMode(String inputValue) {
        this.inputValue = inputValue;
    }

    String inputValue() {
        return inputValue;
    }

    static GrepOutputMode fromInput(Object value) {
        String normalized = value == null ? FILES_WITH_MATCHES.inputValue : value.toString();
        normalized = normalized.trim().toLowerCase(Locale.ROOT);
        for (GrepOutputMode mode : values()) {
            if (mode.inputValue.equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("output_mode 不支持: " + value);
    }
}
