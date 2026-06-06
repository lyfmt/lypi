package cn.lypi.agent;

import cn.lypi.agent.compact.AiCompactionSummarizer;
import cn.lypi.agent.compact.CompactSummaryContextBuilder;
import cn.lypi.agent.compact.CompactSummaryInstructionFactory;
import cn.lypi.agent.compact.CompactSummaryRequest;
import cn.lypi.agent.compact.CompactSummaryResult;
import cn.lypi.agent.compact.CompactionSummaryFallbackPolicy;
import cn.lypi.agent.compact.CompactionSummaryOptions;
import cn.lypi.agent.compact.DefaultCompactionSummarizer;
import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.model.AssistantDone;
import cn.lypi.contracts.model.AssistantError;
import cn.lypi.contracts.model.AssistantStart;
import cn.lypi.contracts.model.TextDelta;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.model.TokenUsage;
import cn.lypi.contracts.session.CompactionKind;
import cn.lypi.contracts.session.CompactionPlan;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.SessionEntry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static cn.lypi.agent.AgentCoreTestFixtures.NOW;
import static cn.lypi.agent.AgentCoreTestFixtures.assistantMessage;
import static cn.lypi.agent.AgentCoreTestFixtures.minimalContext;
import static cn.lypi.agent.AgentCoreTestFixtures.userMessage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiCompactionSummarizerTest {
    @Test
    void callsAiProviderWithSummaryContextAndReturnsTextSummary() {
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        provider.enqueue(List.of(
            new AssistantStart("msg-summary"),
            new TextDelta("## Goal\n\n"),
            new TextDelta("- Continue safely."),
            new AssistantDone(Optional.of(new TokenUsage(10, 2, 8, 0)), Optional.of("stop"))
        ));
        AbortSignal abortSignal = () -> false;
        AiCompactionSummarizer summarizer = summarizer(provider, CompactionSummaryOptions.defaults());

        CompactSummaryResult result = summarizer.summarize(request(abortSignal));

        assertThat(result.summary()).contains("## Goal", "Continue safely");
        assertThat(result.usage().inputTokens()).isEqualTo(10);
        assertThat(provider.contexts).hasSize(1);
        assertThat(provider.contexts.getFirst().messages())
            .startsWith(currentContext().messages().toArray(AgentMessage[]::new));
        assertThat(provider.contexts.getFirst().messages()).hasSize(currentContext().messages().size() + 1);
        assertThat(provider.abortSignals).containsExactly(abortSignal);
    }

    @Test
    void fallsBackWhenProviderThrows() {
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        provider.failWith(new IllegalStateException("provider down"));
        AiCompactionSummarizer summarizer = summarizer(provider, CompactionSummaryOptions.defaults());

        CompactSummaryResult result = summarizer.summarize(request(() -> false));

        assertThat(result.summary()).contains("Need compact");
        assertThat(result.usage()).isEqualTo(new TokenUsage(0, 0, 0, 0));
    }

    @Test
    void fallsBackWhenProviderReturnsEmptyText() {
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        provider.enqueue(List.of(new AssistantDone(Optional.empty(), Optional.of("stop"))));
        AiCompactionSummarizer summarizer = summarizer(provider, CompactionSummaryOptions.defaults());

        CompactSummaryResult result = summarizer.summarize(request(() -> false));

        assertThat(result.summary()).contains("Need compact");
    }

    @Test
    void fallsBackWhenProviderSendsAssistantError() {
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        provider.enqueue(List.of(new AssistantError("err-1", "bad stream")));
        AiCompactionSummarizer summarizer = summarizer(provider, CompactionSummaryOptions.defaults());

        CompactSummaryResult result = summarizer.summarize(request(() -> false));

        assertThat(result.summary()).contains("Need compact");
    }

    @Test
    void cleansAnalysisSummaryTagsAndMarkdownFence() {
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        provider.enqueue(List.of(
            new TextDelta("""
                ```markdown
                <analysis>private reasoning</analysis>
                <summary>
                ## Goal

                - Keep the useful part.
                </summary>
                ```
                """),
            new AssistantDone(Optional.of(new TokenUsage(1, 2, 0, 0)), Optional.of("stop"))
        ));
        AiCompactionSummarizer summarizer = summarizer(provider, CompactionSummaryOptions.defaults());

        CompactSummaryResult result = summarizer.summarize(request(() -> false));

        assertThat(result.summary()).contains("## Goal", "Keep the useful part");
        assertThat(result.summary()).doesNotContain("analysis", "summary>", "```", "private reasoning");
    }

    @Test
    void skipCompactionPolicyThrowsInsteadOfFallback() {
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        provider.failWith(new IllegalStateException("provider down"));
        AiCompactionSummarizer summarizer = summarizer(
            provider,
            new CompactionSummaryOptions(ThinkingLevel.OFF, CompactionSummaryFallbackPolicy.SKIP_COMPACTION)
        );

        assertThatThrownBy(() -> summarizer.summarize(request(() -> false)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("provider down");
    }

    @Test
    void abortSignalSkipsFallbackAndPropagatesFailure() {
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        provider.enqueue(List.of(
            new AssistantStart("msg-summary"),
            new TextDelta("partial")
        ));
        AbortSignal abortSignal = new AbortSignal() {
            private int calls;

            @Override
            public boolean aborted() {
                calls++;
                return calls > 1;
            }
        };
        AiCompactionSummarizer summarizer = summarizer(provider, CompactionSummaryOptions.defaults());

        assertThatThrownBy(() -> summarizer.summarize(request(abortSignal)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("aborted");
    }

    private static AiCompactionSummarizer summarizer(
        AgentCoreTestFixtures.StubAiProvider provider,
        CompactionSummaryOptions options
    ) {
        return new AiCompactionSummarizer(
            provider,
            new CompactSummaryContextBuilder(new CompactSummaryInstructionFactory()),
            new DefaultCompactionSummarizer(),
            options
        );
    }

    private static CompactSummaryRequest request(AbortSignal abortSignal) {
        return new CompactSummaryRequest(currentContext(), plan(), branchEntries(), abortSignal);
    }

    private static ContextSnapshot currentContext() {
        return minimalContext(List.of(
            userMessage("msg-user-1", "Need compact"),
            assistantMessage("msg-assistant-1", "Working")
        ));
    }

    private static CompactionPlan plan() {
        return new CompactionPlan(
            "entry-user-1",
            "entry-assistant-1",
            List.of("entry-user-1"),
            CompactionKind.SESSION
        );
    }

    private static List<SessionEntry> branchEntries() {
        return List.of(
            new MessageEntry("entry-user-1", "", currentContext().messages().get(0), NOW),
            new MessageEntry("entry-assistant-1", "entry-user-1", currentContext().messages().get(1), NOW)
        );
    }
}
