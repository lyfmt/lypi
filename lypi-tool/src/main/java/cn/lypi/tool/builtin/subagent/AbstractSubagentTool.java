package cn.lypi.tool.builtin.subagent;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.subagent.SubagentToolPolicy;
import cn.lypi.contracts.tool.InterruptBehavior;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

abstract class AbstractSubagentTool implements Tool<Map<String, Object>, String> {
    private static final int DEFAULT_MAX_RESULT_SIZE = 16_384;
    private static final List<String> BASE_READ_TOOLS = List.of("read", "grep", "glob");
    private static final List<String> PERMISSION_MODE_VALUES = List.of("DEFAULT_EXECUTE", "ACCEPT_EDITS", "BYPASS");
    private static final List<String> AGENT_MODE_VALUES = List.of("PLAN", "EXECUTE");

    @Override
    public List<String> aliases() {
        return List.of();
    }

    @Override
    public JsonSchema inputSchema() {
        return new JsonSchema(Map.of(
            "type", "object",
            "properties", Map.of()
        ));
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolUseContext context) {
        return new ValidationResult(true, List.of());
    }

    @Override
    public PermissionDecision checkPermissions(Map<String, Object> input, ToolUseContext context) {
        return new PermissionDecision(
            PermissionBehavior.ALLOW,
            PermissionDecisionReason.TOOL_SPECIFIC,
            "subagent mailbox 工具自身允许，运行时策略继续参与判定。",
            Optional.<PermissionUpdate>empty(),
            Map.of("tool", name())
        );
    }

    @Override
    public InterruptBehavior interruptBehavior() {
        return InterruptBehavior.CANCEL;
    }

    @Override
    public boolean isConcurrencySafe(Map<String, Object> input) {
        return isReadOnly(input);
    }

    @Override
    public boolean isDestructive(Map<String, Object> input) {
        return false;
    }

    @Override
    public int maxResultSize() {
        return DEFAULT_MAX_RESULT_SIZE;
    }

    @Override
    public AgentMessage serializeForContext(String output) {
        return toolMessage("toolu_unknown", output, false);
    }

    @Override
    public String renderForUser(Map<String, Object> input) {
        return name() + " " + input;
    }

    protected ValidationResult requireAny(Map<String, Object> input, String... names) {
        for (String name : names) {
            Object value = input.get(name);
            if (value != null && !value.toString().isBlank()) {
                return new ValidationResult(true, List.of());
            }
        }
        return new ValidationResult(false, List.of(String.join("/", names) + " 不能为空。"));
    }

    protected ToolResult<String> success(ToolUseContext context, String text) {
        return new ToolResult<>(text, false, List.of(toolMessage(toolUseId(context), text, false)), Optional.empty());
    }

    protected ToolResult<String> error(ToolUseContext context, String message) {
        String text = message == null || message.isBlank() ? "工具调用失败。" : message;
        return new ToolResult<>(text, true, List.of(toolMessage(toolUseId(context), text, true)), Optional.empty());
    }

    protected String stringInput(Map<String, Object> input, String... names) {
        Object value = value(input, names);
        return value == null ? "" : value.toString();
    }

    protected Optional<String> optionalStringInput(Map<String, Object> input, String... names) {
        String value = stringInput(input, names);
        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    protected int intInput(Map<String, Object> input, int defaultValue, String... names) {
        Object value = value(input, names);
        if (value == null || value.toString().isBlank()) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(value.toString());
    }

    protected List<String> stringListInput(Map<String, Object> input, String... names) {
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

    protected PermissionMode permissionMode(Map<String, Object> input, ToolUseContext context) {
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
                    .formatted(value, String.join(", ", PERMISSION_MODE_VALUES))
            );
        }
    }

    protected Optional<ModelSelection> model(Map<String, Object> input) {
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

    protected Optional<ThinkingLevel> thinkingLevel(Map<String, Object> input) {
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

    protected Optional<AgentMode> agentMode(Map<String, Object> input) {
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
                    .formatted(value, String.join(", ", AGENT_MODE_VALUES))
            );
        }
    }

    protected Map<String, Object> permissionModeSchema() {
        return Map.of(
            "type", "string",
            "enum", PERMISSION_MODE_VALUES,
            "description", "子 Agent 权限模式。默认请省略或使用 DEFAULT_EXECUTE；兼容 useDefault/use_default。"
        );
    }

    protected Map<String, Object> agentModeSchema() {
        return Map.of(
            "type", "string",
            "enum", AGENT_MODE_VALUES,
            "description", "子 Agent 模式。通用执行任务请省略或使用 EXECUTE；兼容 general。"
        );
    }

    protected Map<String, Object> thinkingLevelSchema() {
        return Map.of(
            "type", "string",
            "enum", List.of("LOW", "MEDIUM", "HIGH", "MAX"),
            "description", "推理强度。通常省略以继承父 session；只有用户明确指定时填写。可用值: LOW, MEDIUM, HIGH, MAX。"
        );
    }

    protected Map<String, Object> modelSchema() {
        return Map.of(
            "type", "string",
            "description", "子 Agent 模型。通常省略以继承父 session 当前模型；只有用户明确要求某个模型时填写。裸模型名使用当前唯一 provider/openai，provider/model 形式仅用于兼容。"
        );
    }

    private String normalizeEnumToken(String value) {
        return value.trim()
            .replace('-', '_')
            .replaceAll("([a-z])([A-Z])", "$1_$2")
            .toUpperCase(Locale.ROOT);
    }

    protected SubagentToolPolicy toolPolicy(Map<String, Object> input) {
        java.util.LinkedHashSet<String> requested = new java.util.LinkedHashSet<>();
        requested.addAll(stringListInput(input, "tools"));
        requested.addAll(stringListInput(input, "allowedTools", "allowed_tools"));
        java.util.LinkedHashSet<String> effective = new java.util.LinkedHashSet<>(BASE_READ_TOOLS);
        effective.addAll(requested);
        return new SubagentToolPolicy(List.copyOf(requested), List.copyOf(effective));
    }

    protected Path cwd(Map<String, Object> input, ToolUseContext context) {
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

    protected Object value(Map<String, Object> input, String... names) {
        for (String name : names) {
            if (input.containsKey(name)) {
                return input.get(name);
            }
        }
        return null;
    }

    protected String toolUseId(ToolUseContext context) {
        Object value = context.metadata().get("toolUseId");
        return value == null ? "toolu_unknown" : value.toString();
    }

    private AgentMessage toolMessage(String toolUseId, String text, boolean error) {
        return new AgentMessage(
            "msg_" + toolUseId,
            MessageRole.TOOL_RESULT,
            MessageKind.TOOL_RESULT,
            List.of(new ToolResultContentBlock(toolUseId, text, error)),
            Instant.now(),
            Optional.empty(),
            Optional.empty()
        );
    }
}
