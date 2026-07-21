package cn.lypi.tool.builtin.subagent;

import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.model.ThinkingLevel;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class SubagentToolInputs {
    private static final Set<String> SPAWN_FIELDS = Set.of(
        "task_name",
        "message",
        "agent",
        "tools",
        "provider",
        "model",
        "thinking_level"
    );
    private static final Set<String> WAIT_FIELDS = Set.of("timeout_ms");

    private SubagentToolInputs() {
    }

    static ValidationResult validateSpawn(Map<String, Object> input) {
        List<String> errors = exactFields(input, SPAWN_FIELDS);
        requireString(input, "task_name", errors);
        requireString(input, "message", errors);
        validateOptionalString(input, "agent", errors);
        validateOptionalString(input, "provider", errors);
        validateOptionalString(input, "model", errors);
        tryValue(errors, () -> tools(input));
        tryValue(errors, () -> thinkingLevel(input));
        return new ValidationResult(errors.isEmpty(), List.copyOf(errors));
    }

    static ValidationResult validateWait(Map<String, Object> input) {
        List<String> errors = exactFields(input, WAIT_FIELDS);
        tryValue(errors, () -> timeoutMillis(input));
        return new ValidationResult(errors.isEmpty(), List.copyOf(errors));
    }

    static String requiredString(Map<String, Object> input, String name) {
        Object value = input == null ? null : input.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空。");
        }
        return text;
    }

    static Optional<String> optionalString(Map<String, Object> input, String name) {
        if (input == null || !input.containsKey(name)) {
            return Optional.empty();
        }
        Object value = input.get(name);
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(name + " 必须是字符串。");
        }
        String normalized = text.trim();
        return normalized.isEmpty() ? Optional.empty() : Optional.of(normalized);
    }

    static List<String> tools(Map<String, Object> input) {
        if (input == null || !input.containsKey("tools")) {
            return List.of();
        }
        Object value = input.get("tools");
        if (!(value instanceof Collection<?> collection)) {
            throw new IllegalArgumentException("tools 必须是 canonical 工具名数组。");
        }
        List<String> tools = new ArrayList<>(collection.size());
        for (Object item : collection) {
            if (!(item instanceof String name) || name.isBlank()) {
                throw new IllegalArgumentException("tools 只能包含非空 canonical 工具名。");
            }
            tools.add(name);
        }
        return List.copyOf(tools);
    }

    static Optional<ThinkingLevel> thinkingLevel(Map<String, Object> input) {
        if (input == null || !input.containsKey("thinking_level")) {
            return Optional.empty();
        }
        Object value = input.get("thinking_level");
        if (value instanceof ThinkingLevel level) {
            return Optional.of(level);
        }
        if (!(value instanceof String name)) {
            throw new IllegalArgumentException("thinking_level 必须是 canonical 枚举名。");
        }
        String normalized = name.trim();
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ThinkingLevel.valueOf(normalized));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "thinking_level 不支持: " + normalized + "。允许值: "
                    + String.join(", ", SubagentToolSchemas.THINKING_LEVEL_VALUES)
            );
        }
    }

    static long timeoutMillis(Map<String, Object> input) {
        if (input == null || !input.containsKey("timeout_ms")) {
            return SubagentToolSchemas.DEFAULT_TIMEOUT_MILLIS;
        }
        Object value = input.get("timeout_ms");
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("timeout_ms 必须是整数毫秒值。");
        }
        long timeout;
        try {
            timeout = new BigDecimal(number.toString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException("timeout_ms 必须是整数毫秒值。");
        }
        if (timeout < 0 || timeout > SubagentToolSchemas.MAX_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException(
                "timeout_ms 必须在 0 到 " + SubagentToolSchemas.MAX_TIMEOUT_MILLIS + " 之间。"
            );
        }
        return timeout;
    }

    private static List<String> exactFields(Map<String, Object> input, Set<String> allowed) {
        List<String> errors = new ArrayList<>();
        if (input == null) {
            errors.add("工具输入不能为空。");
            return errors;
        }
        LinkedHashSet<String> unknown = new LinkedHashSet<>(input.keySet());
        unknown.removeAll(allowed);
        if (!unknown.isEmpty()) {
            errors.add("不支持的参数: " + String.join(", ", unknown));
        }
        return errors;
    }

    private static void requireString(Map<String, Object> input, String name, List<String> errors) {
        tryValue(errors, () -> requiredString(input, name));
    }

    private static void validateOptionalString(Map<String, Object> input, String name, List<String> errors) {
        tryValue(errors, () -> optionalString(input, name));
    }

    private static void tryValue(List<String> errors, Runnable parser) {
        try {
            parser.run();
        } catch (IllegalArgumentException exception) {
            errors.add(exception.getMessage());
        }
    }
}
