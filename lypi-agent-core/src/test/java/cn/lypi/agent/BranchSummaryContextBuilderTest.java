package cn.lypi.agent;

import cn.lypi.agent.branch.BranchSummaryContextBuilder;
import cn.lypi.agent.branch.BranchSummaryInstructionFactory;
import cn.lypi.agent.branch.BranchSummaryRequest;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.session.BranchSummaryEntry;
import cn.lypi.contracts.session.BranchSummaryPlan;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.ModelChangeEntry;
import cn.lypi.contracts.session.SessionEntry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static cn.lypi.agent.AgentCoreTestFixtures.NOW;
import static cn.lypi.agent.AgentCoreTestFixtures.assistantMessage;
import static cn.lypi.agent.AgentCoreTestFixtures.minimalContext;
import static cn.lypi.agent.AgentCoreTestFixtures.userMessage;
import static org.assertj.core.api.Assertions.assertThat;

class BranchSummaryContextBuilderTest {
    @Test
    void buildsStandaloneSummaryPromptFromOnlyEntriesToSummarize() {
        BranchSummaryContextBuilder builder = new BranchSummaryContextBuilder(new BranchSummaryInstructionFactory());
        ContextSnapshot currentContext = minimalContext(List.of(
            userMessage("msg-current", "target branch context that must not be summarized")
        ));
        AgentMessage oldUser = userMessage("msg-old-user", "old branch user");
        AgentMessage oldAssistant = assistantMessage("msg-old-assistant", "old branch assistant");
        List<SessionEntry> entries = List.of(
            new ModelChangeEntry("entry-model", "shared", currentContext.model(), "test", NOW),
            new MessageEntry("entry-old-user", "shared", oldUser, NOW),
            new MessageEntry("entry-old-assistant", "entry-old-user", oldAssistant, NOW),
            new BranchSummaryEntry("entry-old-summary", "entry-old-assistant", "entry-nested-old", "nested branch summary", NOW)
        );
        BranchSummaryPlan plan = new BranchSummaryPlan("entry-old-summary", "entry-target", Optional.of("shared"), entries);

        ContextSnapshot summaryContext = builder.build(new BranchSummaryRequest(currentContext, plan, () -> false));

        assertThat(summaryContext.systemPrompt()).isEqualTo(currentContext.systemPrompt());
        assertThat(summaryContext.model()).isEqualTo(currentContext.model());
        assertThat(summaryContext.messages()).hasSize(4);
        assertThat(summaryContext.messages())
            .extracting(message -> message.content().getFirst().text())
            .containsExactly(
                "old branch user",
                "old branch assistant",
                "nested branch summary",
                summaryContext.messages().getLast().content().getFirst().text()
            );
        assertThat(summaryContext.messages().getLast().role()).isEqualTo(MessageRole.USER);
        assertThat(summaryContext.messages().getLast().kind()).isEqualTo(MessageKind.TEXT);
        assertThat(summaryContext.messages().getLast().content().getFirst().text())
            .contains("conversation branch", "structured summary", "不要调用工具");
        assertThat(summaryContext.messages())
            .extracting(message -> message.content().getFirst().text())
            .doesNotContain("target branch context that must not be summarized");
    }
}
