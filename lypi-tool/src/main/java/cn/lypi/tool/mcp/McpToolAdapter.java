package cn.lypi.tool.mcp;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.mcp.McpToolSchema;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.tool.InterruptBehavior;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class McpToolAdapter implements Tool<Map<String, Object>, Object> {
    private final McpToolSchema schema;
    private final McpToolInvoker invoker;
    private final String name;

    public McpToolAdapter(McpToolSchema schema, McpToolInvoker invoker) {
        this.schema = Objects.requireNonNull(schema, "schema must not be null");
        this.invoker = Objects.requireNonNull(invoker, "invoker must not be null");
        this.name = McpToolName.format(schema.serverName(), schema.toolName());
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<String> aliases() {
        if (schema.lyPiToolName() == null || schema.lyPiToolName().isBlank() || schema.lyPiToolName().equals(name)) {
            return List.of();
        }
        return List.of(schema.lyPiToolName());
    }

    @Override
    public JsonSchema inputSchema() {
        return schema.inputSchema();
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolUseContext context) {
        return new ValidationResult(true, List.of());
    }

    @Override
    public PermissionDecision checkPermissions(Map<String, Object> input, ToolUseContext context) {
        return new PermissionDecision(
            PermissionBehavior.ASK,
            PermissionDecisionReason.TOOL_SPECIFIC,
            "调用 MCP 工具需要确认。",
            Optional.<PermissionUpdate>empty(),
            Map.of("serverName", schema.serverName(), "toolName", schema.toolName())
        );
    }

    @Override
    public ToolResult<Object> execute(Map<String, Object> input, ToolUseContext context, ProgressSink progress) {
        String toolUseId = toolUseId(context);
        try {
            Map<String, Object> arguments = input == null ? Map.of() : input;
            progress.progress(ToolProgress.custom(
                "mcp tool invoking",
                Map.of("serverName", schema.serverName(), "toolName", schema.toolName())
            ));
            Object output = invoker.invoke(schema.serverName(), schema.toolName(), arguments, context, progress);
            if (output instanceof McpToolCallResult callResult) {
                return result(toolUseId, callResult.output(), callResult.error());
            }
            return result(toolUseId, output, false);
        } catch (RuntimeException exception) {
            return result(toolUseId, "MCP 工具调用失败: " + exception.getMessage(), true);
        }
    }

    @Override
    public InterruptBehavior interruptBehavior() {
        return InterruptBehavior.CANCEL;
    }

    @Override
    public boolean isReadOnly(Map<String, Object> input) {
        return false;
    }

    @Override
    public boolean isConcurrencySafe(Map<String, Object> input) {
        return false;
    }

    @Override
    public boolean isDestructive(Map<String, Object> input) {
        return true;
    }

    @Override
    public int maxResultSize() {
        return 16_384;
    }

    @Override
    public String renderForUser(Map<String, Object> input) {
        return name + " " + (input == null ? Map.of() : input);
    }

    @Override
    public AgentMessage serializeForContext(Object output) {
        return toolMessage("toolu_unknown", String.valueOf(output), false);
    }

    private ToolResult<Object> result(String toolUseId, Object output, boolean error) {
        return new ToolResult<>(
            output,
            error,
            List.of(toolMessage(toolUseId, String.valueOf(output), error)),
            Optional.empty()
        );
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

    private String toolUseId(ToolUseContext context) {
        Object value = context.metadata().get("toolUseId");
        return value == null ? "toolu_unknown" : value.toString();
    }
}
