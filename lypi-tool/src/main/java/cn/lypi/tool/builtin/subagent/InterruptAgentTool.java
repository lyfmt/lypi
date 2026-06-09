package cn.lypi.tool.builtin.subagent;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.runtime.AgentCenterPort;
import cn.lypi.contracts.subagent.MailboxCommandResult;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class InterruptAgentTool extends AbstractSubagentTool {
    private final AgentCenterPort agentCenter;

    public InterruptAgentTool(AgentCenterPort agentCenter) {
        this.agentCenter = Objects.requireNonNull(agentCenter, "agentCenter must not be null");
    }

    @Override
    public String name() {
        return "interrupt_agent";
    }

    @Override
    public String description() {
        return "中断一个正在运行的 subagent。";
    }

    @Override
    public JsonSchema inputSchema() {
        return new JsonSchema(Map.of(
            "type", "object",
            "required", List.of("agentId"),
            "properties", Map.of(
                "agentId", Map.of("type", "string")
            )
        ));
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolUseContext context) {
        return requireAny(input, "agentId", "agent_id");
    }

    @Override
    public ToolResult<String> execute(Map<String, Object> input, ToolUseContext context, ProgressSink progress) {
        progress.progress(ToolProgress.phase("interrupting", "中断 subagent"));
        String agentId = stringInput(input, "agentId", "agent_id");
        MailboxCommandResult result = agentCenter.interrupt(agentId);
        if (!result.success()) {
            return error(context, result.errorMessage().orElse("中断 subagent 失败。"));
        }
        return success(context, "中断请求已发送: " + agentId);
    }

    @Override
    public boolean isReadOnly(Map<String, Object> input) {
        return false;
    }
}
