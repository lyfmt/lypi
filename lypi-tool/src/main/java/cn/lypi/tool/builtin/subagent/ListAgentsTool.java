package cn.lypi.tool.builtin.subagent;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.runtime.AgentRegistryPort;
import cn.lypi.contracts.subagent.AgentRunStatus;
import cn.lypi.contracts.subagent.AgentView;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class ListAgentsTool extends AbstractSubagentTool {
    private final AgentRegistryPort registry;

    public ListAgentsTool(AgentRegistryPort registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    @Override
    public String name() {
        return "list_agents";
    }

    @Override
    public String description() {
        return "列出当前 session 下的 subagent 状态。";
    }

    @Override
    public JsonSchema inputSchema() {
        return new JsonSchema(Map.of(
            "type", "object",
            "properties", Map.of(
                "status", Map.of("type", "string"),
                "statuses", Map.of("type", "array", "items", Map.of("type", "string"))
            )
        ));
    }

    @Override
    public ToolResult<String> execute(Map<String, Object> input, ToolUseContext context, ProgressSink progress) {
        try {
            progress.progress(ToolProgress.phase("listing", "读取 subagent 列表"));
            List<AgentView> agents = registry.list(context.sessionId(), statuses(input));
            if (agents.isEmpty()) {
                return success(context, "当前 session 没有 subagent。");
            }
            return success(context, agents.stream()
                .map(this::render)
                .collect(Collectors.joining("\n\n")));
        } catch (IllegalArgumentException exception) {
            return error(context, exception.getMessage());
        }
    }

    @Override
    public boolean isReadOnly(Map<String, Object> input) {
        return true;
    }

    private Set<AgentRunStatus> statuses(Map<String, Object> input) {
        List<String> values = stringListInput(input, "statuses", "status");
        if (values.isEmpty()) {
            return Set.of();
        }
        EnumSet<AgentRunStatus> statuses = EnumSet.noneOf(AgentRunStatus.class);
        for (String value : values) {
            try {
                statuses.add(AgentRunStatus.valueOf(value.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("未知 agent status: " + value, exception);
            }
        }
        return Set.copyOf(statuses);
    }

    private String render(AgentView view) {
        return """
            agentId: %s
            label: %s
            childSessionId: %s
            status: %s
            mailboxStatus: %s
            finalEntryId: %s
            summary: %s
            """.formatted(
            view.agentId(),
            view.label(),
            view.childSessionId(),
            view.status(),
            view.mailboxStatus().map(Enum::name).orElse(""),
            view.finalEntryId().orElse(""),
            view.summary().orElse("")
        ).trim();
    }
}
