package cn.lypi.ai.model;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class CompatSanitizer {
    private CompatSanitizer() {
    }

    public static Map<String, Object> sanitize(Map<String, Object> compat) {
        Map<String, Object> sanitized = new LinkedHashMap<>(Objects.requireNonNull(compat, "compat"));
        sanitized.keySet().removeIf(CompatSanitizer::sensitiveCompatKey);
        return Map.copyOf(sanitized);
    }

    public static boolean sensitiveCompatKey(String key) {
        String normalized = key.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
        return normalized.contains("secret")
            || normalized.contains("credential")
            || normalized.contains("password")
            || normalized.contains("token")
            || normalized.contains("authorization")
            || normalized.contains("apikey")
            || normalized.endsWith("key");
    }
}
