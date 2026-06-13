package cn.lypi.tool.builtin.subagent;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.runtime.AgentCenterPort;
import cn.lypi.contracts.subagent.SubagentContinueRequest;
import cn.lypi.contracts.subagent.SubagentContinueResult;
import cn.lypi.contracts.subagent.SubagentRunStatus;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
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
        return new JsonSchema(Map.of(
            "type", "object",
            "required", List.of("childSessionId", "prompt"),
            "properties", Map.of(
                "childSessionId", Map.of("type", "string"),
                "child_session_id", Map.of("type", "string"),
                "prompt", Map.of("type", "string"),
                "cwd", Map.of("type", "string"),
                "timeoutSeconds", Map.of("type", "integer", "minimum", 1),
                "timeout_seconds", Map.of("type", "integer", "minimum", 1),
                "tools", Map.of("type", "array", "items", Map.of("type", "string")),
                "allowedTools", Map.of("type", "array", "items", Map.of("type", "string")),
                "allowed_tools", Map.of("type", "array", "items", Map.of("type", "string"))
            )
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
            SubagentContinueResult result = agentCenter.continueRun(new SubagentContinueRequest(
                context.sessionId(),
                parentEntryId(context),
                stringInput(input, "childSessionId", "child_session_id"),
                stringInput(input, "prompt"),
                cwd(input, context),
                tools(input),
                intInput(input, 600, "timeoutSeconds", "timeout_seconds")
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

    private List<String> tools(Map<String, Object> input) {
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
        merged.addAll(stringListInput(input, "tools"));
        merged.addAll(stringListInput(input, "allowedTools", "allowed_tools"));
        return List.copyOf(merged);
    }

    private String parentEntryId(ToolUseContext context) {
        Object parentEntryId = context.metadata().get("parentEntryId");
        if (parentEntryId instanceof String value && !value.isBlank()) {
            return value;
        }
        return null;
    }
}
