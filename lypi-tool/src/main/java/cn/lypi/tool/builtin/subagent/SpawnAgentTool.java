package cn.lypi.tool.builtin.subagent;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.runtime.AgentCenterPort;
import cn.lypi.contracts.runtime.ToolRuntimePort;
import cn.lypi.contracts.subagent.ExpertAgentDefinition;
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
    private final SubagentToolPolicyNormalizer toolPolicyNormalizer;
    private final ExpertAgentResolver expertAgentResolver;

    public SpawnAgentTool(ToolRuntimePort toolRuntime, AgentCenterPort agentCenter) {
        this(toolRuntime, agentCenter, List.of());
    }

    public SpawnAgentTool(
        ToolRuntimePort toolRuntime,
        AgentCenterPort agentCenter,
        List<ExpertAgentDefinition> expertAgents
    ) {
        this.agentCenter = Objects.requireNonNull(agentCenter, "agentCenter must not be null");
        this.toolPolicyNormalizer = new SubagentToolPolicyNormalizer(toolRuntime);
        this.expertAgentResolver = new ExpertAgentResolver(expertAgents);
    }

    @Override
    public String name() {
        return "spawn_agent";
    }

    @Override
    public String description() {
        return "启动一个 prompt-only subagent。可选择已配置的专家 Agent；专家提供 provider、model、prompt 与 tools 默认值，显式参数可覆盖。"
            + "未选择专家时默认继承当前 Agent 的 provider、model 与 thinking level；仅在明确需要覆盖时填写这些可选参数。"
            + "read、grep、glob 固定可用，tools 只追加 canonical 工具名。启动后继续执行其他独立工作；completion 会在后续模型边界自动投递。"
            + "仅当下一步依赖结果且没有其他可执行工作时才调用 wait_agent；用户要求继续时不要调用 wait_agent。";
    }

    @Override
    public JsonSchema inputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("task_name", Map.of("type", "string"));
        properties.put("message", Map.of("type", "string"));
        properties.put("agent", Map.of(
            "type", "string",
            "enum", expertAgentResolver.names(),
            "description", "可选专家 Agent 名；使用其 provider、model、prompt 与 tools 默认配置，显式参数可覆盖默认值。"
        ));
        properties.put("tools", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("provider", SubagentToolSchemas.providerSchema());
        properties.put("model", SubagentToolSchemas.modelSchema());
        properties.put("thinking_level", SubagentToolSchemas.thinkingLevelSchema());
        return new JsonSchema(Map.of(
            "type", "object",
            "required", List.of("task_name", "message"),
            "properties", properties,
            "additionalProperties", false
        ));
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolUseContext context) {
        ValidationResult fields = SubagentToolInputs.validateSpawn(input);
        if (!fields.valid()) {
            return fields;
        }
        try {
            resolveInput(input);
            return fields;
        } catch (IllegalArgumentException exception) {
            return new ValidationResult(false, List.of(exception.getMessage()));
        }
    }

    @Override
    public ToolResult<String> execute(Map<String, Object> input, ToolUseContext context, ProgressSink progress) {
        try {
            ValidationResult fields = SubagentToolInputs.validateSpawn(input);
            if (!fields.valid()) {
                return error(context, String.join(" ", fields.messages()));
            }
            ResolvedSpawn resolved = resolveInput(input);
            progress.progress(ToolProgress.phase("spawning", "启动 subagent"));
            SubagentSpawnResult result = agentCenter.spawn(new SubagentSpawnRequest(
                context.sessionId(),
                parentEntryId(context),
                SubagentToolInputs.requiredString(input, "task_name"),
                SubagentToolInputs.requiredString(input, "message"),
                resolved.policy().effectiveTools(),
                resolved.configuration().provider(),
                resolved.configuration().model(),
                SubagentToolInputs.thinkingLevel(input),
                resolved.configuration().agentRole(),
                resolved.configuration().initialSystemPrompt()
            ));
            if (result.status() == SubagentRunStatus.FAILED) {
                return error(context, result.message().orElse("subagent 启动失败。"));
            }
            return success(context, """
                Subagent 已启动。
                taskName: %s
                agentId: %s
                childSessionId: %s
                runId: %s
                status: %s
                completion 会在后续模型边界自动投递。请继续执行其他独立工作；仅当下一步依赖该结果且没有其他可执行工作时调用 wait_agent。用户要求继续时不要等待。
                """.formatted(
                    result.taskName(),
                    result.agentId(),
                    result.childSessionId(),
                    result.runId(),
                    result.status()
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
        Object value = context.metadata().get("parentEntryId");
        return value instanceof String parentEntryId && !parentEntryId.isBlank() ? parentEntryId : null;
    }

    private ResolvedSpawn resolveInput(Map<String, Object> input) {
        ExpertAgentResolver.Resolved expert = expertAgentResolver.resolve(input);
        return new ResolvedSpawn(expert, toolPolicyNormalizer.normalize(expert.requestedTools()));
    }

    private record ResolvedSpawn(
        ExpertAgentResolver.Resolved configuration,
        SubagentToolPolicy policy
    ) {}
}
