package cn.lypi.tool.builtin.subagent;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.runtime.AgentCenterPort;
import cn.lypi.contracts.subagent.SubagentContinueRequest;
import cn.lypi.contracts.subagent.SubagentContinueResult;
import cn.lypi.contracts.subagent.SubagentRunStatus;
import cn.lypi.contracts.subagent.SubagentToolPolicy;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ContinueAgentTool extends AbstractSubagentTool {
    private final AgentCenterPort agentCenter;

    public ContinueAgentTool(AgentCenterPort agentCenter) {
        this.agentCenter = Objects.requireNonNull(agentCenter, "agentCenter must not be null");
    }

    @Override
    public String name() {
        return "continue_agent";
    }

    @Override
    public String description() {
        return "向已有 child subagent session 追加一轮新输入。";
    }

    @Override
    public JsonSchema inputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("childSessionId", Map.of("type", "string"));
        properties.put("child_session_id", Map.of("type", "string"));
        properties.put("prompt", Map.of("type", "string"));
        properties.put("cwd", Map.of("type", "string"));
        properties.put("timeoutSeconds", Map.of("type", "integer", "minimum", 1));
        properties.put("timeout_seconds", Map.of("type", "integer", "minimum", 1));
        properties.put("tools", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("allowedTools", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("allowed_tools", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("model", Map.of("type", "string"));
        properties.put("modelId", Map.of("type", "string"));
        properties.put("thinkingLevel", thinkingLevelSchema());
        properties.put("thinking", thinkingLevelSchema());
        properties.put("mode", agentModeSchema());
        properties.put("agentMode", agentModeSchema());
        properties.put("permissionMode", permissionModeSchema());
        properties.put("permission_mode", permissionModeSchema());
        return new JsonSchema(Map.of(
            "type", "object",
            "required", List.of("childSessionId", "prompt"),
            "properties", properties
        ));
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolUseContext context) {
        ValidationResult childSession = requireAny(input, "childSessionId", "child_session_id");
        if (!childSession.valid()) {
            return childSession;
        }
        return requireAny(input, "prompt");
    }

    @Override
    public ToolResult<String> execute(Map<String, Object> input, ToolUseContext context, ProgressSink progress) {
        try {
            progress.progress(ToolProgress.phase("continuing", "继续 subagent"));
            SubagentToolPolicy toolPolicy = toolPolicy(input);
            SubagentContinueResult result = agentCenter.continueRun(new SubagentContinueRequest(
                context.sessionId(),
                parentEntryId(context),
                stringInput(input, "childSessionId", "child_session_id"),
                stringInput(input, "prompt"),
                cwd(input, context),
                toolPolicy.effectiveTools(),
                toolPolicy,
                permissionMode(input, context),
                intInput(input, 600, "timeoutSeconds", "timeout_seconds"),
                model(input),
                thinkingLevel(input),
                agentMode(input)
            ));
            if (result.status() == SubagentRunStatus.FAILED) {
                return error(context, result.message().orElse("subagent continue 失败。"));
            }
            return success(context, """
                Subagent 已继续。
                agentId: %s
                childSessionId: %s
                parentSessionId: %s
                parentContinueEntryId: %s
                runId: %s
                status: %s
                message: %s
                """.formatted(
                    result.agentId(),
                    result.childSessionId(),
                    result.parentSessionId(),
                    result.parentContinueEntryId(),
                    result.runId(),
                    result.status(),
                    result.message().orElse("")
                ).trim());
        } catch (IllegalArgumentException exception) {
            return error(context, exception.getMessage());
        }
    }

    @Override
    public boolean isReadOnly(Map<String, Object> input) {
        return false;
    }

    private String parentEntryId(ToolUseContext context) {
        Object parentEntryId = context.metadata().get("parentEntryId");
        if (parentEntryId instanceof String value && !value.isBlank()) {
            return value;
        }
        return null;
    }
}
