package cn.lycode.agent;

import cn.lycode.agent.branch.AiBranchSummarizer;
import cn.lycode.agent.branch.BranchSummaryContextBuilder;
import cn.lycode.agent.branch.BranchSummaryInstructionFactory;
import cn.lycode.agent.branch.BranchSummaryRequest;
import cn.lycode.agent.branch.BranchSummaryResult;
import cn.lycode.contracts.common.AbortSignal;
import cn.lycode.contracts.context.ContextSnapshot;
import cn.lycode.contracts.model.AssistantDone;
import cn.lycode.contracts.model.AssistantStart;
import cn.lycode.contracts.model.TextDelta;
import cn.lycode.contracts.model.TokenUsage;
import cn.lycode.contracts.session.BranchSummaryPlan;
import cn.lycode.contracts.session.MessageEntry;
import cn.lycode.contracts.session.SessionEntry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static cn.lycode.agent.AgentCoreTestFixtures.NOW;
import static cn.lycode.agent.AgentCoreTestFixtures.minimalContext;
import static cn.lycode.agent.AgentCoreTestFixtures.userMessage;
import static org.assertj.core.api.Assertions.assertThat;

class AiBranchSummarizerTest {
    @Test
    void callsAiProviderWithBranchSummaryContextAndReturnsSummary() {
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        provider.enqueue(List.of(
            new AssistantStart("msg-summary"),
            new TextDelta("## Goal\n"),
            new TextDelta("Preserve abandoned work."),
            new AssistantDone(Optional.of(new TokenUsage(10, 2, 8, 0)), Optional.of("stop"))
        ));
        AbortSignal abortSignal = () -> false;
        ContextSnapshot currentContext = minimalContext(List.of(userMessage("msg-current", "current")));
        List<SessionEntry> entries = List.of(new MessageEntry(
            "entry-old",
            "shared",
            userMessage("msg-old", "old branch"),
            NOW
        ));
        BranchSummaryPlan plan = new BranchSummaryPlan("entry-old", "entry-target", Optional.of("shared"), entries);
        AiBranchSummarizer summarizer = new AiBranchSummarizer(
            provider,
            new BranchSummaryContextBuilder(new BranchSummaryInstructionFactory())
        );

        BranchSummaryResult result = summarizer.summarize(new BranchSummaryRequest(currentContext, plan, abortSignal));

        assertThat(result.summary()).contains("The user explored a different conversation branch", "## Goal", "Preserve abandoned work.");
        assertThat(result.usage().inputTokens()).isEqualTo(10);
        assertThat(provider.contexts).hasSize(1);
        assertThat(provider.contexts.getFirst().messages())
            .extracting(message -> message.content().getFirst().text())
            .contains("old branch")
            .doesNotContain("current");
        assertThat(provider.abortSignals).containsExactly(abortSignal);
    }
}
