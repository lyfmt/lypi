package cn.lypi.tool.builtin.subagent;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.subagent.SubagentToolPolicy;
import cn.lypi.contracts.tool.InterruptBehavior;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.tool.ToolEventSummaryFormatter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

abstract class AbstractSubagentTool implements Tool<Map<String, Object>, String> {
    private static final int DEFAULT_MAX_RESULT_SIZE = 16_384;
    protected static final int DEFAULT_TIMEOUT_SECONDS = SubagentToolSchemas.DEFAULT_TIMEOUT_SECONDS;
    protected static final int MAX_TIMEOUT_SECONDS = SubagentToolSchemas.MAX_TIMEOUT_SECONDS;

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
        return SubagentToolMessages.serializeForContext(output);
    }

    @Override
    public String renderForUser(Map<String, Object> input) {
        return ToolEventSummaryFormatter.genericInputSummary(name(), input);
    }

    protected ValidationResult requireAny(Map<String, Object> input, String... names) {
        return SubagentToolInputs.requireAny(input, names);
    }

    protected ToolResult<String> success(ToolUseContext context, String text) {
        return SubagentToolMessages.success(context, text);
    }

    protected ToolResult<String> error(ToolUseContext context, String message) {
        return SubagentToolMessages.error(context, message);
    }

    protected String stringInput(Map<String, Object> input, String... names) {
        return SubagentToolInputs.stringInput(input, names);
    }

    protected Optional<String> optionalStringInput(Map<String, Object> input, String... names) {
        return SubagentToolInputs.optionalStringInput(input, names);
    }

    protected int intInput(Map<String, Object> input, int defaultValue, String... names) {
        return SubagentToolInputs.intInput(input, defaultValue, names);
    }

    protected int timeoutSeconds(Map<String, Object> input) {
        return SubagentToolInputs.timeoutSeconds(input);
    }

    protected Map<String, Object> timeoutSecondsSchema() {
        return SubagentToolSchemas.timeoutSecondsSchema();
    }

    protected List<String> stringListInput(Map<String, Object> input, String... names) {
        return SubagentToolInputs.stringListInput(input, names);
    }

    protected PermissionMode permissionMode(Map<String, Object> input, ToolUseContext context) {
        return SubagentToolInputs.permissionMode(input);
    }

    protected PermissionRuntimeState permissionRuntimeState(Map<String, Object> input) {
        return SubagentToolInputs.permissionRuntimeState(input);
    }

    protected boolean permissionRuntimeStateSpecified(Map<String, Object> input) {
        return SubagentToolInputs.permissionRuntimeStateSpecified(input);
    }

    protected Optional<ModelSelection> model(Map<String, Object> input) {
        return SubagentToolInputs.model(input);
    }

    protected Optional<ThinkingLevel> thinkingLevel(Map<String, Object> input) {
        return SubagentToolInputs.thinkingLevel(input);
    }

    protected Optional<AgentMode> agentMode(Map<String, Object> input) {
        return SubagentToolInputs.agentMode(input);
    }

    protected Map<String, Object> permissionModeSchema() {
        return SubagentToolSchemas.permissionModeSchema();
    }

    protected Map<String, Object> permissionRuntimeStateSchema() {
        return SubagentToolSchemas.permissionRuntimeStateSchema();
    }

    protected Map<String, Object> agentModeSchema() {
        return SubagentToolSchemas.agentModeSchema();
    }

    protected Map<String, Object> thinkingLevelSchema() {
        return SubagentToolSchemas.thinkingLevelSchema();
    }

    protected Map<String, Object> modelSchema() {
        return SubagentToolSchemas.modelSchema();
    }

    protected SubagentToolPolicy toolPolicy(Map<String, Object> input) {
        return SubagentToolInputs.toolPolicy(input);
    }

    protected Path cwd(Map<String, Object> input, ToolUseContext context) {
        return SubagentToolInputs.cwd(input, context);
    }

    protected Object value(Map<String, Object> input, String... names) {
        return SubagentToolInputs.value(input, names);
    }

    protected String toolUseId(ToolUseContext context) {
        return SubagentToolMessages.toolUseId(context);
    }
}
