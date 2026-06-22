package cn.lypi.runtime.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.runtime.CompactStateBackfillItem;
import cn.lypi.contracts.runtime.CompactStateBackfillRequest;
import cn.lypi.contracts.runtime.AgentRegistryPort;
import cn.lypi.contracts.subagent.AgentRunStatus;
import cn.lypi.contracts.subagent.AgentView;
import cn.lypi.contracts.subagent.MailboxStatus;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentCompactStateBackfillTest {
    @Test
    void rendersRunningAgentStateAndContinuationGuidance() {
        AgentRegistryPort registry = (sessionId, statuses) -> List.of(new AgentView(
            "agent-1",
            "Researcher [explorer]",
            "session-1",
            "child-session-1",
            "entry-spawn-1",
            AgentRunStatus.RUNNING,
            Optional.of(MailboxStatus.PENDING),
            Optional.of("正在检索资料"),
            Optional.empty(),
            Optional.of("Researcher"),
            Optional.of("explorer")
        ));
        AgentCompactStateBackfill backfill = new AgentCompactStateBackfill(registry);

        List<CompactStateBackfillItem> items = backfill.backfill(request("session-1"));

        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.attachmentId()).isEqualTo("compact-agent-state");
            assertThat(item.title()).contains("Agent");
            assertThat(item.content())
                .contains("agentId: agent-1")
                .contains("childSessionId: child-session-1")
                .contains("status: RUNNING")
                .contains("mailboxStatus: PENDING")
                .contains("summary: 正在检索资料")
                .contains("wait_agent")
                .contains("read_agent_result")
                .contains("read_mailbox")
                .contains("不要重复 spawn_agent");
            assertThat(item.metadata()).containsEntry("backfillType", "agent");
        });
    }

    @Test
    void rendersCompletedAgentWithoutUnfinishedTaskGuidance() {
        AgentRegistryPort registry = (sessionId, statuses) -> List.of(new AgentView(
            "agent-2",
            "Coder [worker]",
            "session-1",
            "child-session-2",
            "entry-spawn-2",
            AgentRunStatus.SUCCEEDED,
            Optional.of(MailboxStatus.DELIVERED),
            Optional.of("实现完成"),
            Optional.of("entry-final-2"),
            Optional.of("Coder"),
            Optional.of("worker")
        ));
        AgentCompactStateBackfill backfill = new AgentCompactStateBackfill(registry);

        List<CompactStateBackfillItem> items = backfill.backfill(request("session-1"));

        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.content())
                .contains("agentId: agent-2")
                .contains("status: SUCCEEDED")
                .contains("finalEntryId: entry-final-2")
                .doesNotContain("不要重复 spawn_agent");
        });
    }

    @Test
    void returnsEmptyWhenRegistryHasNoAgents() {
        AgentCompactStateBackfill backfill = new AgentCompactStateBackfill((sessionId, statuses) -> {
            assertThat(statuses).isEqualTo(Set.of());
            return List.of();
        });

        assertThat(backfill.backfill(request("session-1"))).isEmpty();
    }

    @Test
    void truncatesLargeAgentListAndKeepsAgentCountMetadata() {
        AgentRegistryPort registry = (sessionId, statuses) -> List.of(new AgentView(
            "agent-large",
            "Large",
            "session-1",
            "child-session-large",
            "entry-spawn-large",
            AgentRunStatus.RUNNING,
            Optional.empty(),
            Optional.of("A".repeat(20_000)),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        ));
        AgentCompactStateBackfill backfill = new AgentCompactStateBackfill(registry);

        List<CompactStateBackfillItem> items = backfill.backfill(request("session-1"));

        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.content()).contains("内容已截断").hasSizeLessThanOrEqualTo(12_000);
            assertThat(item.metadata()).containsEntry("agentCount", "1");
            assertThat(item.metadata()).containsEntry("truncated", "true");
        });
    }

    private static CompactStateBackfillRequest request(String sessionId) {
        return new CompactStateBackfillRequest(sessionId, Path.of("."), null, null, List.of());
    }
}
