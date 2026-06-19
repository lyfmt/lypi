package cn.lypi.tool.builtin.subagent;

import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.subagent.SubagentToolPolicy;
import cn.lypi.contracts.tool.ToolUseContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

final class SubagentToolInputs {
    private static final List<String> BASE_READ_TOOLS = List.of("read", "grep", "glob");
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper()
        .registerModule(new Jdk8Module())
        .findAndRegisterModules();

    private SubagentToolInputs() {
    }

    static ValidationResult requireAny(Map<String, Object> input, String... names) {
        for (String name : names) {
            Object value = input.get(name);
            if (value != null && !value.toString().isBlank()) {
                return new ValidationResult(true, List.of());
            }
        }
        return new ValidationResult(false, List.of(String.join("/", names) + " 不能为空。"));
    }

    static String stringInput(Map<String, Object> input, String... names) {
        Object value = value(input, names);
        return value == null ? "" : value.toString();
    }

    static Optional<String> optionalStringInput(Map<String, Object> input, String... names) {
        String value = stringInput(input, names);
        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    static int intInput(Map<String, Object> input, int defaultValue, String... names) {
        Object value = value(input, names);
        if (value == null || value.toString().isBlank()) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(value.toString());
    }

    static int timeoutSeconds(Map<String, Object> input) {
        int value = intInput(input, SubagentToolSchemas.DEFAULT_TIMEOUT_SECONDS, "timeoutSeconds", "timeout_seconds");
        return Math.max(1, Math.min(value, SubagentToolSchemas.MAX_TIMEOUT_SECONDS));
    }

    static List<String> stringListInput(Map<String, Object> input, String... names) {
        Object value = value(input, names);
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> collection) {
            List<String> values = new ArrayList<>();
            for (Object item : collection) {
                if (item != null && !item.toString().isBlank()) {
                    values.add(item.toString());
                }
            }
            return List.copyOf(values);
        }
        String text = value.toString();
        if (text.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String item : text.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isBlank()) {
                values.add(trimmed);
            }
        }
        return List.copyOf(values);
    }

    static PermissionMode permissionMode(Map<String, Object> input) {
        Object value = value(input, "permissionMode", "permission_mode");
        if (value instanceof PermissionMode permissionMode) {
            return permissionMode;
        }
        if (value == null || value.toString().isBlank()) {
            return PermissionMode.DEFAULT_EXECUTE;
        }
        String normalized = normalizeEnumToken(value.toString());
        if (normalized.equals("USEDEFAULT") || normalized.equals("USE_DEFAULT") || normalized.equals("DEFAULT")) {
            return PermissionMode.DEFAULT_EXECUTE;
        }
        try {
            return PermissionMode.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "permissionMode 不支持: %s。允许值: %s。默认执行模式请省略该字段，或使用 DEFAULT_EXECUTE；兼容别名: useDefault/use_default。"
                    .formatted(value, String.join(", ", SubagentToolSchemas.PERMISSION_MODE_VALUES))
            );
        }
    }

    static PermissionRuntimeState permissionRuntimeState(Map<String, Object> input) {
        Object value = value(input, "permissionRuntimeState", "permission_runtime_state");
        if (value instanceof PermissionRuntimeState runtimeState) {
            return runtimeState;
        }
        if (value != null) {
            return JSON_MAPPER.convertValue(value, PermissionRuntimeState.class);
        }
        return PermissionRuntimeState.fromLegacy(permissionMode(input));
    }

    static boolean permissionRuntimeStateSpecified(Map<String, Object> input) {
        return value(input, "permissionRuntimeState", "permission_runtime_state", "permissionMode", "permission_mode") != null;
    }

    static Optional<ModelSelection> model(Map<String, Object> input) {
        Object value = value(input, "model", "modelId", "model_id");
        if (value == null || value.toString().isBlank()) {
            return Optional.empty();
        }
        String raw = value.toString().trim();
        String provider = "openai";
        String modelId = raw;
        int separator = raw.indexOf('/');
        if (separator > 0 && separator < raw.length() - 1) {
            provider = raw.substring(0, separator);
            modelId = raw.substring(separator + 1);
        }
        return Optional.of(new ModelSelection(provider, modelId, thinkingLevel(input).orElse(ThinkingLevel.MEDIUM)));
    }

    static Optional<ThinkingLevel> thinkingLevel(Map<String, Object> input) {
        Object value = value(input, "thinkingLevel", "thinking_level", "thinking");
        if (value instanceof ThinkingLevel thinkingLevel) {
            return Optional.of(thinkingLevel);
        }
        if (value == null || value.toString().isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ThinkingLevel.valueOf(normalizeEnumToken(value.toString())));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "thinkingLevel 不支持: %s。允许值: LOW, MEDIUM, HIGH, MAX。".formatted(value)
            );
        }
    }

    static Optional<AgentMode> agentMode(Map<String, Object> input) {
        Object value = value(input, "agentMode", "agent_mode", "mode");
        if (value instanceof AgentMode agentMode) {
            return Optional.of(agentMode);
        }
        if (value == null || value.toString().isBlank()) {
            return Optional.empty();
        }
        String normalized = normalizeEnumToken(value.toString());
        if (normalized.equals("GENERAL") || normalized.equals("DEFAULT")) {
            return Optional.of(AgentMode.EXECUTE);
        }
        try {
            return Optional.of(AgentMode.valueOf(normalized));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "mode/agentMode 不支持: %s。允许值: %s。通用执行任务请省略该字段或使用 EXECUTE；兼容别名: general。"
                    .formatted(value, String.join(", ", SubagentToolSchemas.AGENT_MODE_VALUES))
            );
        }
    }

    static SubagentToolPolicy toolPolicy(Map<String, Object> input) {
        LinkedHashSet<String> requested = new LinkedHashSet<>();
        requested.addAll(stringListInput(input, "tools"));
        requested.addAll(stringListInput(input, "allowedTools", "allowed_tools"));
        LinkedHashSet<String> effective = new LinkedHashSet<>(BASE_READ_TOOLS);
        effective.addAll(requested);
        return new SubagentToolPolicy(List.copyOf(requested), List.copyOf(effective));
    }

    static Path cwd(Map<String, Object> input, ToolUseContext context) {
        String raw = stringInput(input, "cwd");
        if (raw.isBlank()) {
            return context.cwd();
        }
        Path cwd = context.cwd().toAbsolutePath().normalize();
        Path resolved = cwd.resolve(raw).normalize();
        if (!resolved.startsWith(cwd)) {
            throw new IllegalArgumentException("cwd 越过当前工作目录: " + raw);
        }
        return resolved;
    }

    static Object value(Map<String, Object> input, String... names) {
        for (String name : names) {
            if (input.containsKey(name)) {
                return input.get(name);
            }
        }
        return null;
    }

    private static String normalizeEnumToken(String value) {
        return value.trim()
            .replace('-', '_')
            .replaceAll("([a-z])([A-Z])", "$1_$2")
            .toUpperCase(Locale.ROOT);
    }
}
