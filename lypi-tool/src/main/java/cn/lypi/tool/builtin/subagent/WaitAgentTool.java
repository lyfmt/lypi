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
import cn.lypi.tool.ToolAbortSupport;
import cn.lypi.tool.ToolSteeringSupport;
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
        return "阻塞等待当前 session 的 subagent completion。completion 原本会在后续模型边界自动投递；"
            + "仅当下一步依赖结果且没有其他可执行工作时调用。用户要求继续执行时不要调用 wait_agent。"
            + "用户输入、中断或超时也会结束等待。";
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
            SubagentToolInputs.timeoutMillis(input),
            ToolAbortSupport.signal(context),
            ToolSteeringSupport.source(context)
        ));
        return success(context, render(result));
    }

    @Override
    public boolean isReadOnly(Map<String, Object> input) {
        return false;
    }

    private String render(SubagentWaitResult result) {
        return switch (result.outcome()) {
            case STEERED -> "等待已被新的用户输入唤醒。";
            case ABORTED -> "等待已中断。";
            case TIMED_OUT -> "等待结束，subagent 尚未回复。";
            case COMPLETED -> """
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
        };
    }
}
