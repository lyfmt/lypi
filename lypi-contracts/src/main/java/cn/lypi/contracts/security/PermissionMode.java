package cn.lypi.contracts.security;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum PermissionMode {
    ASK,
    AUTO,
    BYPASS;

    @JsonCreator
    public static PermissionMode fromJson(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("permission mode must not be blank");
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "ASK", "DEFAULT_EXECUTE" -> ASK;
            case "AUTO", "ACCEPT_EDITS" -> AUTO;
            case "BYPASS" -> BYPASS;
            default -> throw new IllegalArgumentException("unsupported permission mode: " + value);
        };
    }

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }
}
