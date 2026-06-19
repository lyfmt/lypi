package cn.lypi.contracts.security;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum FileSystemSpecialPath {
    ROOT(":root"),
    MINIMAL(":minimal"),
    PROJECT_ROOTS(":workspace_roots"),
    TMPDIR(":tmpdir"),
    SLASH_TMP(":slash_tmp");

    private final String jsonValue;

    FileSystemSpecialPath(String jsonValue) {
        this.jsonValue = jsonValue;
    }

    @JsonValue
    public String jsonValue() {
        return jsonValue;
    }

    @JsonCreator
    public static FileSystemSpecialPath fromJson(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("special path must not be blank");
        }
        for (FileSystemSpecialPath path : values()) {
            if (path.jsonValue.equals(value)) {
                return path;
            }
        }
        throw new IllegalArgumentException("unknown special path: " + value);
    }
}
