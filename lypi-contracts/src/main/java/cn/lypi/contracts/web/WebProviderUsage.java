package cn.lypi.contracts.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 记录 Web provider 调用元数据。
 *
 * NOTE: metadata 不保存 API key、Authorization 或 token 等敏感字段。
 */
public record WebProviderUsage(
    String provider,
    Optional<String> requestId,
    Map<String, Object> metadata
) {
    public WebProviderUsage {
        requestId = requestId == null ? Optional.empty() : requestId;
        metadata = sanitized(metadata);
    }

    private static Map<String, Object> sanitized(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> filtered = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!sensitiveKey(key)) {
                filtered.put(key, value);
            }
        });
        return Map.copyOf(filtered);
    }

    private static boolean sensitiveKey(String key) {
        if (key == null) {
            return true;
        }
        String normalized = key.replace("-", "").replace("_", "").toLowerCase();
        return normalized.contains("apikey")
            || normalized.contains("authorization")
            || normalized.contains("token")
            || normalized.contains("secret")
            || normalized.contains("password");
    }
}
