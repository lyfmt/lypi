package cn.lypi.contracts.security;

import java.util.Objects;

public record FileSystemPath(
    Kind kind,
    String value
) {
    public FileSystemPath {
        kind = Objects.requireNonNull(kind, "kind");
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
        if (kind == Kind.SPECIAL) {
            FileSystemSpecialPath.fromJson(value);
        } else if (value.startsWith(":")) {
            throw new IllegalArgumentException(kind + " path must not use special path token");
        }
    }

    public static FileSystemPath special(FileSystemSpecialPath specialPath) {
        return new FileSystemPath(Kind.SPECIAL, Objects.requireNonNull(specialPath, "specialPath").jsonValue());
    }

    public static FileSystemPath exactPath(String path) {
        return new FileSystemPath(Kind.EXACT_PATH, path);
    }

    public static FileSystemPath globPattern(String pattern) {
        return new FileSystemPath(Kind.GLOB_PATTERN, pattern);
    }

    public enum Kind {
        SPECIAL,
        EXACT_PATH,
        GLOB_PATTERN
    }
}
