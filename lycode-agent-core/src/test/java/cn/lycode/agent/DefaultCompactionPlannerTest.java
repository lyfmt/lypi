package cn.lycode.agent;

import cn.lycode.agent.compact.DefaultCompactionPlanner;
import cn.lycode.contracts.context.ContextBudget;
import cn.lycode.contracts.context.ContextSnapshot;
import cn.lycode.contracts.context.MessageKind;
import cn.lycode.contracts.prompt.SystemPrompt;
import cn.lycode.contracts.security.PermissionMode;
import cn.lycode.contracts.session.CompactionEntry;
import cn.lycode.contracts.session.CompactionKind;
import cn.lycode.contracts.session.CompactionPlan;
import cn.lycode.contracts.session.MessageEntry;
import cn.lycode.contracts.session.SessionEntry;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static cn.lycode.agent.AgentCoreTestFixtures.NOW;
import static cn.lycode.agent.AgentCoreTestFixtures.assistantMessage;
import static cn.lycode.agent.AgentCoreTestFixtures.assistantToolCallMessage;
import static cn.lycode.agent.AgentCoreTestFixtures.toolResultMessage;
import static cn.lycode.agent.AgentCoreTestFixtures.userMessage;
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
        assertThat(plan.orElseThrow().firstKeptEntryId()).isEqualTo("entry-assistant-2");
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
    void keepsWholeApiRoundForParallelToolCalls() {
        DefaultCompactionPlanner planner = new DefaultCompactionPlanner(8);
        List<SessionEntry> branchEntries = List.of(
            messageEntry("entry-user-1", "", userMessage("msg-user-1", "user one long")),
            messageEntry(
                "entry-assistant-tools",
                "entry-user-1",
                assistantToolCallMessage("msg-assistant-tools", "tool-call-1", "read", java.util.Map.of("path", "a.txt"))
            ),
            messageEntry("entry-tool-result-1", "entry-assistant-tools", toolResultMessage("msg-tool-result-1", "tool-call-1", "result one long", false)),
            messageEntry("entry-tool-result-2", "entry-tool-result-1", toolResultMessage("msg-tool-result-2", "tool-call-2", "result two long", false)),
            messageEntry("entry-assistant-2", "entry-tool-result-2", assistantMessage("msg-assistant-2", "next assistant long")),
            messageEntry("entry-user-2", "entry-assistant-2", userMessage("msg-user-2", "recent user long"))
        );

        Optional<CompactionPlan> plan = planner.plan(branchEntries, overBudgetContext());

        assertThat(plan).isPresent();
        assertThat(plan.orElseThrow().firstKeptEntryId()).isEqualTo("entry-assistant-2");
        assertThat(plan.orElseThrow().summarizedEntryIds())
            .containsExactly("entry-user-1", "entry-assistant-tools", "entry-tool-result-1", "entry-tool-result-2");
    }

    @Test
    void canCompactInsideSingleUserTurnAtNewAssistantApiRound() {
        DefaultCompactionPlanner planner = new DefaultCompactionPlanner(4);
        List<SessionEntry> branchEntries = List.of(
            messageEntry("entry-user-1", "", userMessage("msg-user-1", "single prompt long")),
            messageEntry(
                "entry-assistant-tool-1",
                "entry-user-1",
                assistantToolCallMessage("msg-assistant-tool-1", "tool-call-1", "read", java.util.Map.of("path", "a.txt"))
            ),
            messageEntry("entry-tool-result-1", "entry-assistant-tool-1", toolResultMessage("msg-tool-result-1", "tool-call-1", "tool result long", false)),
            messageEntry("entry-assistant-2", "entry-tool-result-1", assistantMessage("msg-assistant-2", "follow up assistant long")),
            messageEntry("entry-assistant-3", "entry-assistant-2", assistantMessage("msg-assistant-3", "final assistant long"))
        );

        Optional<CompactionPlan> plan = planner.plan(branchEntries, overBudgetContext());

        assertThat(plan).isPresent();
        assertThat(plan.orElseThrow().firstKeptEntryId()).isEqualTo("entry-assistant-3");
        assertThat(plan.orElseThrow().summarizedEntryIds())
            .containsExactly("entry-user-1", "entry-assistant-tool-1", "entry-tool-result-1", "entry-assistant-2");
    }

    @Test
    void danglingToolCallDoesNotPinApiRoundBoundaryClosedForever() {
        DefaultCompactionPlanner planner = new DefaultCompactionPlanner(4);
        List<SessionEntry> branchEntries = List.of(
            messageEntry("entry-user-1", "", userMessage("msg-user-1", "single prompt long")),
            messageEntry(
                "entry-assistant-dangling",
                "entry-user-1",
                assistantToolCallMessage("msg-assistant-dangling", "tool-call-1", "read", java.util.Map.of("path", "a.txt"))
            ),
            messageEntry("entry-assistant-2", "entry-assistant-dangling", assistantMessage("msg-assistant-2", "new api round long")),
            messageEntry("entry-assistant-3", "entry-assistant-2", assistantMessage("msg-assistant-3", "recent api round long"))
        );

        Optional<CompactionPlan> plan = planner.plan(branchEntries, overBudgetContext());

        assertThat(plan).isPresent();
        assertThat(plan.orElseThrow().firstKeptEntryId()).isEqualTo("entry-assistant-3");
        assertThat(plan.orElseThrow().summarizedEntryIds())
            .containsExactly("entry-user-1", "entry-assistant-dangling", "entry-assistant-2");
    }

    @Test
    void sameAssistantIdEntriesStayInOneApiRound() {
        DefaultCompactionPlanner planner = new DefaultCompactionPlanner(4);
        List<SessionEntry> branchEntries = List.of(
            messageEntry("entry-user-1", "", userMessage("msg-user-1", "user one long")),
            messageEntry("entry-assistant-text", "entry-user-1", assistantMessage("msg-assistant-same", "assistant text long")),
            messageEntry(
                "entry-assistant-tool",
                "entry-assistant-text",
                assistantToolCallMessage("msg-assistant-same", "tool-call-1", "read", java.util.Map.of("path", "a.txt"))
            ),
            messageEntry("entry-tool-result", "entry-assistant-tool", toolResultMessage("msg-tool-result", "tool-call-1", "result long", false)),
            messageEntry("entry-assistant-2", "entry-tool-result", assistantMessage("msg-assistant-2", "new round long"))
        );

        Optional<CompactionPlan> plan = planner.plan(branchEntries, overBudgetContext());

        assertThat(plan).isPresent();
        assertThat(plan.orElseThrow().firstKeptEntryId()).isEqualTo("entry-assistant-text");
        assertThat(plan.orElseThrow().summarizedEntryIds())
            .containsExactly("entry-user-1");
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

    private static MessageEntry messageEntry(String id, String parentId, cn.lycode.contracts.context.AgentMessage message) {
        return new MessageEntry(id, parentId, message, NOW);
    }

    private static ContextSnapshot overBudgetContext() {
        return new ContextSnapshot(
            new SystemPrompt("system", List.of("test"), "hash"),
            List.of(),
            null,
            null,
            null,
            PermissionMode.ASK,
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
