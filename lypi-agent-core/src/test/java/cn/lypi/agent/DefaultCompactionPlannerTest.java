package cn.lypi.agent;

import cn.lypi.agent.compact.DefaultCompactionPlanner;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.session.CompactionEntry;
import cn.lypi.contracts.session.CompactionKind;
import cn.lypi.contracts.session.CompactionPlan;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.SessionEntry;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static cn.lypi.agent.AgentCoreTestFixtures.NOW;
import static cn.lypi.agent.AgentCoreTestFixtures.assistantMessage;
import static cn.lypi.agent.AgentCoreTestFixtures.toolResultMessage;
import static cn.lypi.agent.AgentCoreTestFixtures.userMessage;
import static org.assertj.core.api.Assertions.assertThat;

class DefaultCompactionPlannerTest {
    @Test
    void createsPlanWhenContextExceedsBudget() {
        DefaultCompactionPlanner planner = new DefaultCompactionPlanner(4);
        List<SessionEntry> branchEntries = List.of(
            messageEntry("entry-user-1", "", userMessage("msg-user-1", "user one long")),
            messageEntry("entry-assistant-1", "entry-user-1", assistantMessage("msg-assistant-1", "assistant one long")),
            messageEntry("entry-user-2", "entry-assistant-1", userMessage("msg-user-2", "user two long")),
            messageEntry("entry-assistant-2", "entry-user-2", assistantMessage("msg-assistant-2", "assistant two long")),
            messageEntry("entry-tool-result-2", "entry-assistant-2", toolResultMessage("msg-tool-result-2", "tool-call-2", "tool result two long", false)),
            messageEntry("entry-user-3", "entry-tool-result-2", userMessage("msg-user-3", "user three long"))
        );

        Optional<CompactionPlan> plan = planner.plan(branchEntries, overBudgetContext());

        assertThat(plan).isPresent();
        assertThat(plan.orElseThrow().firstKeptEntryId()).isEqualTo("entry-user-3");
        assertThat(plan.orElseThrow().summarizedEntryIds()).contains("entry-user-1", "entry-assistant-1");
        assertThat(plan.orElseThrow().kind()).isEqualTo(CompactionKind.SESSION);
    }

    @Test
    void doesNotCutAtToolResult() {
        DefaultCompactionPlanner planner = new DefaultCompactionPlanner(7);
        List<SessionEntry> branchEntries = List.of(
            messageEntry("entry-user-1", "", userMessage("msg-user-1", "user one long")),
            messageEntry("entry-assistant-1", "entry-user-1", assistantMessage("msg-assistant-1", "assistant one long")),
            messageEntry("entry-tool-result-1", "entry-assistant-1", toolResultMessage("msg-tool-result-1", "tool-call-1", "tool result one long", false)),
            messageEntry("entry-user-2", "entry-tool-result-1", userMessage("msg-user-2", "user two long"))
        );

        Optional<CompactionPlan> plan = planner.plan(branchEntries, overBudgetContext());

        assertThat(plan).isPresent();
        assertThat(plan.orElseThrow().firstKeptEntryId()).isNotEqualTo("entry-tool-result-1");
        assertThat(entryKind(branchEntries, plan.orElseThrow().firstKeptEntryId())).isNotEqualTo(MessageKind.TOOL_RESULT);
    }

    @Test
    void skipsWhenLatestEntryIsCompaction() {
        DefaultCompactionPlanner planner = new DefaultCompactionPlanner(4);
        List<SessionEntry> branchEntries = List.of(
            messageEntry("entry-user-1", "", userMessage("msg-user-1", "user one long")),
            new CompactionEntry("entry-compact", "entry-user-1", "summary", "entry-user-1", 100, 20, CompactionKind.SESSION, NOW)
        );

        Optional<CompactionPlan> plan = planner.plan(branchEntries, overBudgetContext());

        assertThat(plan).isEmpty();
    }

    private static MessageEntry messageEntry(String id, String parentId, cn.lypi.contracts.context.AgentMessage message) {
        return new MessageEntry(id, parentId, message, NOW);
    }

    private static ContextSnapshot overBudgetContext() {
        return new ContextSnapshot(
            new SystemPrompt("system", List.of("test"), "hash"),
            List.of(),
            null,
            null,
            null,
            null,
            new ContextBudget(101, 128_000, 100, 8_192, 16_384, 0, 0, BigDecimal.ZERO)
        );
    }

    private static MessageKind entryKind(List<SessionEntry> entries, String entryId) {
        return entries.stream()
            .filter(entry -> entry.id().equals(entryId))
            .filter(MessageEntry.class::isInstance)
            .map(MessageEntry.class::cast)
            .map(entry -> entry.message().kind())
            .findFirst()
            .orElseThrow();
    }
}
