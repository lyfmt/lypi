package cn.lypi.tool.builtin.subagent;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.tool.InterruptBehavior;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.tool.ToolEventSummaryFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

abstract class AbstractSubagentTool implements Tool<Map<String, Object>, String> {
    private static final int DEFAULT_MAX_RESULT_SIZE = 16_384;

    @Override
    public List<String> aliases() {
        return List.of();
    }

    @Override
    public JsonSchema inputSchema() {
        return new JsonSchema(Map.of("type", "object", "properties", Map.of()));
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
            "subagent 工具自身允许，运行时策略继续参与判定。",
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
        return SubagentToolMessages.serializeForContext(output);
    }

    @Override
    public String renderForUser(Map<String, Object> input) {
        return ToolEventSummaryFormatter.genericInputSummary(name(), input);
    }

    protected ToolResult<String> success(ToolUseContext context, String text) {
        return SubagentToolMessages.success(context, text);
    }

    protected ToolResult<String> error(ToolUseContext context, String message) {
        return SubagentToolMessages.error(context, message);
    }
}
