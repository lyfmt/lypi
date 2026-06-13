package cn.lypi.tool.builtin.subagent;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.runtime.AgentCenterPort;
import cn.lypi.contracts.subagent.SubagentRunStatus;
import cn.lypi.contracts.subagent.SubagentWaitRequest;
import cn.lypi.contracts.subagent.SubagentWaitResult;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
        return "等待指定 subagent run 完成，并返回该 run 的状态、摘要和错误信息。wait_agent 返回 FAILED 表示子 Agent run 失败，"
            + "不是等待工具失败；此时应继续使用 read_agent_result 或 read_mailbox 读取子 Agent 结果/错误，"
            + "不要改由父 Agent 自己完成原任务。";
    }

    @Override
    public JsonSchema inputSchema() {
        return new JsonSchema(Map.of(
            "type", "object",
            "properties", Map.of(
                "agentId", Map.of("type", "string"),
                "agent_id", Map.of("type", "string"),
                "childSessionId", Map.of("type", "string"),
                "child_session_id", Map.of("type", "string"),
                "runId", Map.of("type", "string"),
                "run_id", Map.of("type", "string"),
                "timeoutSeconds", timeoutSecondsSchema(),
                "timeout_seconds", timeoutSecondsSchema()
            )
        ));
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolUseContext context) {
        return requireAny(input, "agentId", "agent_id", "childSessionId", "child_session_id");
    }

    @Override
    public ToolResult<String> execute(Map<String, Object> input, ToolUseContext context, ProgressSink progress) {
        progress.progress(ToolProgress.phase("waiting", "等待 subagent 完成"));
        SubagentWaitResult result = agentCenter.waitFor(new SubagentWaitRequest(
            optionalStringInput(input, "agentId", "agent_id"),
            optionalStringInput(input, "childSessionId", "child_session_id"),
            optionalStringInput(input, "runId", "run_id"),
            timeoutSeconds(input),
            true
        ));
        return success(context, render(result));
    }

    @Override
    public boolean isReadOnly(Map<String, Object> input) {
        return false;
    }

    private String render(SubagentWaitResult result) {
        return """
            Subagent wait result.
            agentId: %s
            childSessionId: %s
            runId: %s
            status: %s
            summary: %s
            finalEntryId: %s
            errorMessage: %s
            nextStep: %s
            """.formatted(
                result.agentId(),
                result.childSessionId(),
                result.runId(),
                result.status(),
                result.summary().orElse(""),
                result.finalEntryId().orElse(""),
                result.errorMessage().orElse(""),
                nextStep(result)
            ).trim();
    }

    private String nextStep(SubagentWaitResult result) {
        if (result.status() == SubagentRunStatus.SUCCEEDED || result.status() == SubagentRunStatus.FAILED) {
            return "调用 read_agent_result(childSessionId) 读取完整结果；如需 mailbox 审计，再调用 read_mailbox。";
        }
        if (result.status() == SubagentRunStatus.TIMED_OUT) {
            return "继续 wait_agent 或按需 interrupt_agent；不要假定子 Agent 已完成。";
        }
        return "";
    }
}
