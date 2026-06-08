package cn.lypi.tool.builtin.subagent;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.runtime.AgentCenterPort;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.subagent.SubagentRunStatus;
import cn.lypi.contracts.subagent.SubagentSpawnRequest;
import cn.lypi.contracts.subagent.SubagentSpawnResult;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
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
        return new JsonSchema(Map.of(
            "type", "object",
            "required", List.of("prompt"),
            "properties", Map.of(
                "prompt", Map.of("type", "string"),
                "cwd", Map.of("type", "string"),
                "timeoutSeconds", Map.of("type", "integer", "minimum", 1),
                "agentName", Map.of("type", "string"),
                "role", Map.of("type", "string"),
                "agentRole", Map.of("type", "string"),
                "allowedTools", Map.of("type", "array", "items", Map.of("type", "string"))
            )
        ));
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolUseContext context) {
        return requireAny(input, "prompt");
    }

    @Override
    public ToolResult<String> execute(Map<String, Object> input, ToolUseContext context, ProgressSink progress) {
        try {
            ToolResult<String> unsupportedIsolation = rejectUnsupportedIsolation(input, context);
            if (unsupportedIsolation != null) {
                return unsupportedIsolation;
            }
            progress.progress(ToolProgress.phase("spawning", "启动 subagent"));
            SubagentSpawnResult result = agentCenter.spawn(new SubagentSpawnRequest(
                context.sessionId(),
                context.messageId(),
                stringInput(input, "prompt"),
                cwd(input, context),
                List.of(),
                PermissionMode.DEFAULT_EXECUTE,
                intInput(input, 600, "timeoutSeconds", "timeout_seconds"),
                optionalStringInput(input, "agentName", "agent_name"),
                optionalStringInput(input, "role", "agentRole", "agent_role")
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

    private ToolResult<String> rejectUnsupportedIsolation(Map<String, Object> input, ToolUseContext context) {
        if (!stringListInput(input, "allowedTools", "allowed_tools").isEmpty()) {
            return error(context, "暂不支持为 subagent 单独设置 allowedTools；未启动 subagent。");
        }
        Object permissionMode = input.getOrDefault("permissionMode", input.get("permission_mode"));
        if (permissionMode != null) {
            return error(context, "暂不支持为 subagent 单独设置 permissionMode；未启动 subagent。");
        }
        return null;
    }

    @Override
    public boolean isReadOnly(Map<String, Object> input) {
        return false;
    }
}
