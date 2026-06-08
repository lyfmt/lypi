package cn.lypi.tool.builtin.subagent;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.runtime.AgentCenterPort;
import cn.lypi.contracts.subagent.HeadlessSubagentOutput;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ReadAgentResultTool extends AbstractSubagentTool {
    private final AgentCenterPort agentCenter;

    public ReadAgentResultTool(AgentCenterPort agentCenter) {
        this.agentCenter = Objects.requireNonNull(agentCenter, "agentCenter must not be null");
    }

    @Override
    public String name() {
        return "read_agent_result";
    }

    @Override
    public String description() {
        return "读取 child session 中已完成的 subagent 结果。";
    }

    @Override
    public JsonSchema inputSchema() {
        return new JsonSchema(Map.of(
            "type", "object",
            "required", List.of("childSessionId"),
            "properties", Map.of(
                "childSessionId", Map.of("type", "string")
            )
        ));
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolUseContext context) {
        return requireAny(input, "childSessionId", "child_session_id");
    }

    @Override
    public ToolResult<String> execute(Map<String, Object> input, ToolUseContext context, ProgressSink progress) {
        progress.progress(ToolProgress.phase("reading", "读取 subagent 结果"));
        String childSessionId = stringInput(input, "childSessionId", "child_session_id");
        Optional<HeadlessSubagentOutput> result = agentCenter.readResult(childSessionId);
        if (result.isEmpty()) {
            return error(context, "未找到 subagent 结果: " + childSessionId);
        }
        HeadlessSubagentOutput output = result.get();
        return success(context, """
            childSessionId: %s
            status: %s
            summary: %s
            finalEntryId: %s
            errorMessage: %s
            """.formatted(
                output.childSessionId(),
                output.status(),
                output.summary(),
                output.finalEntryId().orElse(""),
                output.errorMessage().orElse("")
            ).trim());
    }

    @Override
    public boolean isReadOnly(Map<String, Object> input) {
        return true;
    }
}
