package cn.lypi.runtime.subagent;

import cn.lypi.contracts.runtime.AgentRegistryPort;
import cn.lypi.contracts.runtime.CompactStateBackfillItem;
import cn.lypi.contracts.runtime.CompactStateBackfillPort;
import cn.lypi.contracts.runtime.CompactStateBackfillRequest;
import cn.lypi.contracts.subagent.AgentRunStatus;
import cn.lypi.contracts.subagent.AgentView;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 将 subagent registry 状态转换为 compact 后模型可见回填项。
 *
 * NOTE: 只回填父 session 可见的 registry/mailbox 摘要，不读取 child session transcript。
 */
public final class AgentCompactStateBackfill implements CompactStateBackfillPort {
    private static final int MAX_CONTENT_CHARS = 12_000;
    private static final String TRUNCATION_NOTICE = "\n\n[内容已截断；如需完整 agent 状态，请调用 list_agents。]";

    private final AgentRegistryPort registry;

    public AgentCompactStateBackfill(AgentRegistryPort registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    @Override
    public List<CompactStateBackfillItem> backfill(CompactStateBackfillRequest request) {
        List<AgentView> agents = registry.list(request.sessionId(), request.leafEntryId(), Set.of());
        if (agents == null || agents.isEmpty()) {
            return List.of();
        }
        TruncatedText content = truncate(agents.stream()
            .map(this::render)
            .collect(Collectors.joining("\n\n")));
        return List.of(new CompactStateBackfillItem(
            "compact-agent-state",
            "Agent State",
            content.text(),
            Map.of(
                "backfillType", "agent",
                "agentCount", Integer.toString(agents.size()),
                "truncated", Boolean.toString(content.truncated())
            )
        ));
    }

    private String render(AgentView view) {
        StringBuilder text = new StringBuilder();
        text.append("agentId: ").append(safe(view.agentId())).append('\n');
        text.append("label: ").append(safe(view.label())).append('\n');
        text.append("childSessionId: ").append(safe(view.childSessionId())).append('\n');
        text.append("status: ").append(view.status() == null ? AgentRunStatus.UNKNOWN : view.status()).append('\n');
        text.append("mailboxStatus: ").append(view.mailboxStatus().map(Enum::name).orElse("")).append('\n');
        text.append("finalEntryId: ").append(view.finalEntryId().orElse("")).append('\n');
        text.append("summary: ").append(view.summary().orElse("")).append('\n');
        if (unfinished(view.status())) {
            text.append("\n未完成任务指导:\n");
            text.append("- 使用 wait_agent 查询该 agent 的完成状态。\n");
            text.append("- 完成后使用 read_agent_result 或 read_mailbox 读取结果。\n");
            text.append("- 不要重复 spawn_agent；compact 只丢失上下文窗口，不代表子任务丢失。\n");
        }
        return text.toString().strip();
    }

    private boolean unfinished(AgentRunStatus status) {
        return status == null || status == AgentRunStatus.RUNNING || status == AgentRunStatus.UNKNOWN;
    }

    private TruncatedText truncate(String value) {
        String safe = safe(value);
        if (safe.length() <= MAX_CONTENT_CHARS) {
            return new TruncatedText(safe, false);
        }
        int prefixChars = Math.max(0, MAX_CONTENT_CHARS - TRUNCATION_NOTICE.length());
        return new TruncatedText(safe.substring(0, prefixChars) + TRUNCATION_NOTICE, true);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record TruncatedText(String text, boolean truncated) {}
}
