package cn.lypi.agent;

import cn.lypi.agent.compact.CompactSummaryContextBuilder;
import cn.lypi.agent.compact.CompactSummaryInstructionFactory;
import cn.lypi.agent.compact.CompactSummaryRequest;
import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.session.CompactionKind;
import cn.lypi.contracts.session.CompactionPlan;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.SessionEntry;
import java.util.List;
import org.junit.jupiter.api.Test;

import static cn.lypi.agent.AgentCoreTestFixtures.NOW;
import static cn.lypi.agent.AgentCoreTestFixtures.assistantMessage;
import static cn.lypi.agent.AgentCoreTestFixtures.minimalContext;
import static cn.lypi.agent.AgentCoreTestFixtures.userMessage;
import static org.assertj.core.api.Assertions.assertThat;

class CompactSummaryContextBuilderTest {
    @Test
    void appendsInstructionAfterFullContextMessagesAndKeepsCurrentModel() {
        CompactSummaryContextBuilder builder = new CompactSummaryContextBuilder(new CompactSummaryInstructionFactory());
        ContextSnapshot currentContext = minimalContext(List.of(
            userMessage("msg-user-1", "old user"),
            assistantMessage("msg-assistant-1", "old assistant"),
            userMessage("msg-user-2", "kept user")
        ));
        CompactionPlan plan = new CompactionPlan(
            "entry-assistant-1",
            "entry-user-2",
            List.of("entry-user-1", "entry-assistant-1"),
            CompactionKind.SESSION
        );
        List<SessionEntry> branchEntries = List.of(
            new MessageEntry("entry-user-1", "", currentContext.messages().get(0), NOW),
            new MessageEntry("entry-assistant-1", "entry-user-1", currentContext.messages().get(1), NOW),
            new MessageEntry("entry-user-2", "entry-assistant-1", currentContext.messages().get(2), NOW)
        );

        ContextSnapshot summaryContext = builder.build(
            new CompactSummaryRequest(currentContext, plan, branchEntries, () -> false)
        );

        assertThat(summaryContext.messages().subList(0, currentContext.messages().size()))
            .containsExactlyElementsOf(currentContext.messages());
        assertThat(summaryContext.messages()).hasSize(currentContext.messages().size() + 1);
        AgentMessage instruction = summaryContext.messages().getLast();
        assertThat(instruction.role()).isIn(MessageRole.USER, MessageRole.SYSTEM_LOCAL);
        assertThat(instruction.content().getFirst().text()).contains("compact summary", "不要调用工具");
        assertThat(summaryContext.model()).isEqualTo(currentContext.model());
        assertThat(summaryContext.thinkingLevel()).isEqualTo(currentContext.thinkingLevel());
    }

    @Test
    void preservesCurrentSystemPromptAndReestimatesBudgetForSummaryContext() {
        CompactSummaryContextBuilder builder = new CompactSummaryContextBuilder(new CompactSummaryInstructionFactory());
        ContextSnapshot currentContext = minimalContext(List.of(
            userMessage("msg-user-1", "keep user preference from messages")
        ));
        CompactionPlan plan = new CompactionPlan(
            "entry-user-1",
            "entry-user-1",
            List.of("entry-user-1"),
            CompactionKind.SESSION
        );
        List<SessionEntry> branchEntries = List.of(
            new MessageEntry("entry-user-1", "", currentContext.messages().getFirst(), NOW)
        );

        ContextSnapshot summaryContext = builder.build(
            new CompactSummaryRequest(currentContext, plan, branchEntries, () -> false)
        );

        assertThat(summaryContext.systemPrompt()).isEqualTo(currentContext.systemPrompt());
        assertThat(summaryContext.budget().estimatedContextTokens())
            .isEqualTo(new ContextBudgetEstimator()
                .estimate(summaryContext.messages())
                .estimatedContextTokens());
    }
}
