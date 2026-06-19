package cn.lypi.tool.builtin;

import java.util.Map;

final class ToolInputs {
    private ToolInputs() {
    }

    static int intInput(Map<String, Object> input, String fieldName, int defaultValue, int min, int max) {
        Object value = input.get(fieldName);
        int parsed = switch (value) {
            case null -> defaultValue;
            case Number number -> number.intValue();
            default -> Integer.parseInt(value.toString());
        };
        return Math.max(min, Math.min(max, parsed));
    }
}
