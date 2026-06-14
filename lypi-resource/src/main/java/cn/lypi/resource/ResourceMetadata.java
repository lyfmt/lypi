package cn.lypi.resource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class ResourceMetadata {
    private ResourceMetadata() {
    }

    static Optional<String> stringValue(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null ? Optional.empty() : Optional.of(String.valueOf(value));
    }

    static List<String> stringList(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        return items.stream().map(String::valueOf).toList();
    }

    static List<cn.lypi.contracts.prompt.PromptParameter> promptParameters(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        List<cn.lypi.contracts.prompt.PromptParameter> parameters = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> metadata = stringKeyMap(map);
                parameters.add(new cn.lypi.contracts.prompt.PromptParameter(
                    stringValue(metadata, "name").orElse(""),
                    stringValue(metadata, "description").orElse(""),
                    booleanValue(metadata.get("required")),
                    stringValue(metadata, "default")
                ));
            }
        }
        return List.copyOf(parameters);
    }

    private static Map<String, Object> stringKeyMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }
}
