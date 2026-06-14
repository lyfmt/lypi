package cn.lypi.tool.builtin;

import java.util.Map;
import java.util.Set;

record GrepQuery(
    String pattern,
    String path,
    String glob,
    String type,
    GrepOutputMode outputMode,
    Integer beforeContext,
    Integer afterContext,
    Integer context,
    Integer shortContext,
    boolean showLineNumbers,
    boolean caseInsensitive,
    Integer headLimit,
    int offset,
    boolean multiline
) {
    private static final Set<String> SUPPORTED_TYPES = Set.of(
        "c", "cpp", "cs", "css", "go", "html", "java", "js", "json", "kotlin",
        "md", "py", "rb", "rs", "scala", "sh", "swift", "toml", "ts", "xml", "yaml"
    );

    static GrepQuery fromInput(Map<String, Object> input) {
        Object patternValue = input == null ? null : input.get("pattern");
        String pattern = patternValue == null ? "" : patternValue.toString();
        if (pattern.isBlank()) {
            throw new IllegalArgumentException("pattern 不能为空。");
        }
        Integer headLimit = integerInput(input, "head_limit", null, 0, Integer.MAX_VALUE);
        if (headLimit == null) {
            headLimit = integerInput(input, "maxResults", null, 1, Integer.MAX_VALUE);
        }
        return new GrepQuery(
            pattern,
            stringInput(input, "path"),
            stringInput(input, "glob"),
            typeInput(input),
            GrepOutputMode.fromInput(input == null ? null : input.get("output_mode")),
            integerInput(input, "-B", null, 0, Integer.MAX_VALUE),
            integerInput(input, "-A", null, 0, Integer.MAX_VALUE),
            integerInput(input, "context", null, 0, Integer.MAX_VALUE),
            integerInput(input, "-C", null, 0, Integer.MAX_VALUE),
            booleanInput(input, "-n", true),
            booleanInput(input, "-i", false),
            headLimit,
            integerInput(input, "offset", 0, 0, Integer.MAX_VALUE),
            booleanInput(input, "multiline", false)
        );
    }

    private static String typeInput(Map<String, Object> input) {
        String type = stringInput(input, "type");
        if (type == null) {
            return null;
        }
        String normalized = type.toLowerCase(java.util.Locale.ROOT);
        if (!SUPPORTED_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("不支持的 type: " + type
                + "。type 使用 ripgrep 文件类型，例如 java、json、md、ts、py、sh、xml、yaml；"
                + "如果不确定，请省略 type。");
        }
        return normalized;
    }

    private static String stringInput(Map<String, Object> input, String fieldName) {
        Object value = input == null ? null : input.get(fieldName);
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString();
    }

    private static Integer integerInput(Map<String, Object> input, String fieldName, Integer defaultValue, int min, int max) {
        Object value = input == null ? null : input.get(fieldName);
        if (value == null) {
            return defaultValue;
        }
        int parsed = switch (value) {
            case Number number -> number.intValue();
            default -> Integer.parseInt(value.toString());
        };
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException(fieldName + " 超出范围。");
        }
        return parsed;
    }

    private static boolean booleanInput(Map<String, Object> input, String fieldName, boolean defaultValue) {
        Object value = input == null ? null : input.get(fieldName);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(value.toString());
    }
}
