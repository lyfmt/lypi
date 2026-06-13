package cn.lypi.tool.builtin.subagent;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.runtime.AgentCenterPort;
import cn.lypi.contracts.subagent.SubagentRunStatus;
import cn.lypi.contracts.subagent.SubagentSpawnRequest;
import cn.lypi.contracts.subagent.SubagentSpawnResult;
import cn.lypi.contracts.subagent.SubagentToolPolicy;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class SpawnAgentTool extends AbstractSubagentTool {
    private final AgentCenterPort agentCenter;

    public SpawnAgentTool(AgentCenterPort agentCenter) {
        this.agentCenter = Objects.requireNonNull(agentCenter, "agentCenter must not be null");
    }

    @Override
    public String name() {
        return "spawn_agent";
    }

    @Override
    public String description() {
        return "启动一个 headless subagent，并仅返回启动状态。";
    }

    @Override
    public JsonSchema inputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("prompt", Map.of("type", "string"));
        properties.put("cwd", Map.of("type", "string"));
        properties.put("timeoutSeconds", Map.of("type", "integer", "minimum", 1));
        properties.put("agentName", Map.of("type", "string"));
        properties.put("role", Map.of("type", "string"));
        properties.put("agentRole", Map.of("type", "string"));
        properties.put("tools", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("allowedTools", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("allowed_tools", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("model", Map.of("type", "string"));
        properties.put("modelId", Map.of("type", "string"));
        properties.put("thinkingLevel", Map.of("type", "string"));
        properties.put("thinking", Map.of("type", "string"));
        properties.put("mode", Map.of("type", "string"));
        properties.put("agentMode", Map.of("type", "string"));
        properties.put("permissionMode", Map.of("type", "string"));
        properties.put("permission_mode", Map.of("type", "string"));
        return new JsonSchema(Map.of(
            "type", "object",
            "required", List.of("prompt"),
            "properties", properties
        ));
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolUseContext context) {
        return requireAny(input, "prompt");
    }

    @Override
    public ToolResult<String> execute(Map<String, Object> input, ToolUseContext context, ProgressSink progress) {
        try {
            progress.progress(ToolProgress.phase("spawning", "启动 subagent"));
            SubagentToolPolicy toolPolicy = toolPolicy(input);
            SubagentSpawnResult result = agentCenter.spawn(new SubagentSpawnRequest(
                context.sessionId(),
                parentEntryId(context),
                stringInput(input, "prompt"),
                cwd(input, context),
                toolPolicy.effectiveTools(),
                toolPolicy,
                permissionMode(input, context),
                intInput(input, 600, "timeoutSeconds", "timeout_seconds"),
                optionalStringInput(input, "agentName", "agent_name"),
                optionalStringInput(input, "role", "agentRole", "agent_role"),
                model(input),
                thinkingLevel(input),
                agentMode(input),
                value(input, "permissionMode", "permission_mode") != null
            ));
            if (result.status() == SubagentRunStatus.FAILED) {
                return error(context, result.message().orElse("subagent 启动失败。"));
            }
            return success(context, """
                Subagent 已启动。
                agentId: %s
                childSessionId: %s
                parentSessionId: %s
                parentSpawnEntryId: %s
                status: %s
                message: %s
                """.formatted(
                    result.agentId(),
                    result.childSessionId(),
                    result.parentSessionId(),
                    result.parentSpawnEntryId(),
                    result.status(),
                    result.message().orElse("")
                ).trim());
        } catch (IllegalArgumentException exception) {
            return error(context, exception.getMessage());
        }
    }

    private String parentEntryId(ToolUseContext context) {
        Object parentEntryId = context.metadata().get("parentEntryId");
        if (parentEntryId instanceof String value && !value.isBlank()) {
            return value;
        }
        return null;
    }

    @Override
    public boolean isReadOnly(Map<String, Object> input) {
        return false;
    }
}
