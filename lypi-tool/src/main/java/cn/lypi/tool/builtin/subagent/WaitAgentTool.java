package cn.lypi.tool.builtin.subagent;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.runtime.AgentCenterPort;
import cn.lypi.contracts.subagent.SubagentWaitRequest;
import cn.lypi.contracts.subagent.SubagentWaitResult;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class WaitAgentTool extends AbstractSubagentTool {
    private final AgentCenterPort agentCenter;

    public WaitAgentTool(AgentCenterPort agentCenter) {
        this.agentCenter = Objects.requireNonNull(agentCenter, "agentCenter must not be null");
    }

    @Override
    public String name() {
        return "wait_agent";
    }

    @Override
    public String description() {
        return "等待当前 session 任意 subagent mailbox 消息；收到时直接返回 completion，超时不改变 child 状态。";
    }

    @Override
    public JsonSchema inputSchema() {
        return new JsonSchema(Map.of(
            "type", "object",
            "properties", Map.of("timeout_ms", SubagentToolSchemas.timeoutMillisSchema()),
            "additionalProperties", false
        ));
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolUseContext context) {
        return SubagentToolInputs.validateWait(input);
    }

    @Override
    public ToolResult<String> execute(Map<String, Object> input, ToolUseContext context, ProgressSink progress) {
        ValidationResult validation = validateInput(input, context);
        if (!validation.valid()) {
            return error(context, String.join(" ", validation.messages()));
        }
        progress.progress(ToolProgress.phase("waiting", "等待 subagent 回复"));
        SubagentWaitResult result = agentCenter.waitFor(new SubagentWaitRequest(
            context.sessionId(),
            SubagentToolInputs.timeoutMillis(input)
        ));
        return success(context, render(result));
    }

    @Override
    public boolean isReadOnly(Map<String, Object> input) {
        return false;
    }

    private String render(SubagentWaitResult result) {
        if (!result.received()) {
            return "等待结束，subagent 尚未回复。";
        }
        return """
            收到 subagent 回复。
            taskName: %s
            agentId: %s
            childSessionId: %s
            runId: %s
            status: %s
            content:
            %s
            """.formatted(
                result.taskName().orElse(""),
                result.agentId().orElse(""),
                result.childSessionId().orElse(""),
                result.runId().orElse(""),
                result.status().map(Enum::name).orElse(""),
                result.content().orElse("")
            ).trim();
    }
}
