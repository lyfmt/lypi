package cn.lypi.transport.tui;

import cn.lypi.contracts.prompt.PromptParameter;
import cn.lypi.contracts.runtime.AgentCenterPort;
import cn.lypi.contracts.runtime.AgentRegistryPort;
import cn.lypi.contracts.subagent.AgentRunStatus;
import cn.lypi.contracts.subagent.AgentView;
import cn.lypi.contracts.subagent.MailboxCommandResult;
import cn.lypi.contracts.tui.SlashCommand;
import cn.lypi.contracts.tui.SlashCommandHandler;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class AgentSlashCommandHandler implements SlashCommandHandler {
    private final AgentRegistryPort registry;
    private final AgentCenterPort agentCenter;
    private final Supplier<String> currentSessionId;
    private String lastOutput = "";

    public AgentSlashCommandHandler(AgentRegistryPort registry, Supplier<String> currentSessionId) {
        this(registry, null, currentSessionId);
    }

    public AgentSlashCommandHandler(
        AgentRegistryPort registry,
        AgentCenterPort agentCenter,
        Supplier<String> currentSessionId
    ) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.agentCenter = agentCenter;
        this.currentSessionId = Objects.requireNonNull(currentSessionId, "currentSessionId must not be null");
    }

    /**
     * 返回 /agent slash command 定义。
     */
    public SlashCommand command() {
        return new SlashCommand(
            "agent",
            "查看或管理当前 session 下的 subagent。",
            List.of(
                new PromptParameter("action", "list 或 interrupt。", false, Optional.of("list")),
                new PromptParameter("agentId", "interrupt 时要中断的 agent id。", false, Optional.empty()),
                new PromptParameter("statuses", "list 时筛选 agent 状态，逗号分隔。", false, Optional.empty())
            ),
            this
        );
    }

    @Override
    public void handle(Map<String, String> arguments) {
        Map<String, String> safeArguments = arguments == null ? Map.of() : arguments;
        String action = action(safeArguments);
        switch (action) {
            case "list" -> list(safeArguments);
            case "interrupt" -> interrupt(safeArguments);
            default -> lastOutput = "未知 agent action: " + action;
        }
    }

    /**
     * 返回最近一次 slash command 的用户可见输出。
     */
    public String lastOutput() {
        return lastOutput;
    }

    private String action(Map<String, String> arguments) {
        String action = value(arguments, "action");
        return action.isBlank() ? "list" : action.toLowerCase(Locale.ROOT);
    }

    private void list(Map<String, String> arguments) {
        Set<AgentRunStatus> statuses;
        try {
            statuses = statuses(arguments);
        } catch (IllegalArgumentException exception) {
            lastOutput = exception.getMessage();
            return;
        }
        List<AgentView> agents = registry.list(currentSessionId.get(), statuses);
        if (agents.isEmpty()) {
            lastOutput = "当前没有匹配 agent。";
            return;
        }
        lastOutput = agents.stream()
            .map(this::render)
            .collect(Collectors.joining("\n\n"));
    }

    private void interrupt(Map<String, String> arguments) {
        if (agentCenter == null) {
            lastOutput = "agent interrupt 未启用。";
            return;
        }
        String agentId = value(arguments, "agentId");
        if (agentId.isBlank()) {
            lastOutput = "agentId 不能为空。";
            return;
        }
        MailboxCommandResult result = agentCenter.interrupt(agentId);
        if (!result.success()) {
            lastOutput = result.errorMessage().orElse("中断 subagent 失败。");
            return;
        }
        lastOutput = "中断请求已发送: " + agentId;
    }

    private Set<AgentRunStatus> statuses(Map<String, String> arguments) {
        String statuses = value(arguments, "statuses");
        if (statuses.isBlank()) {
            return Set.of();
        }
        EnumSet<AgentRunStatus> parsed = EnumSet.noneOf(AgentRunStatus.class);
        for (String status : statuses.split(",")) {
            String normalized = status.trim();
            if (!normalized.isBlank()) {
                try {
                    parsed.add(AgentRunStatus.valueOf(normalized.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("未知 agent status: " + normalized, exception);
                }
            }
        }
        return Set.copyOf(parsed);
    }

    private String render(AgentView agent) {
        return """
            agentId: %s
            label: %s
            childSessionId: %s
            parentSpawnEntryId: %s
            status: %s
            mailboxStatus: %s
            summary: %s
            finalEntryId: %s
            """.formatted(
                agent.agentId(),
                agent.label(),
                agent.childSessionId(),
                agent.parentSpawnEntryId(),
                agent.status(),
                agent.mailboxStatus().map(Enum::name).orElse("-"),
                agent.summary().orElse("-"),
                agent.finalEntryId().orElse("-")
            ).trim();
    }

    private String value(Map<String, String> arguments, String name) {
        String value = arguments.get(name);
        return value == null ? "" : value.trim();
    }
}
