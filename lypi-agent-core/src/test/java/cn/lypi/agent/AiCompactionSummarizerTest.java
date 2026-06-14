package cn.lypi.agent;

import cn.lypi.agent.compact.AiCompactionSummarizer;
import cn.lypi.agent.compact.CompactSummaryContextBuilder;
import cn.lypi.agent.compact.CompactSummaryInstructionFactory;
import cn.lypi.agent.compact.CompactSummaryRequest;
import cn.lypi.agent.compact.CompactSummaryResult;
import cn.lypi.agent.compact.CompactionSummaryFallbackPolicy;
import cn.lypi.agent.compact.CompactionSummaryOptions;
import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.error.ContextOverflowException;
import cn.lypi.contracts.error.ErrorSeverity;
import cn.lypi.contracts.model.AssistantDone;
import cn.lypi.contracts.model.AssistantError;
import cn.lypi.contracts.model.AssistantStart;
import cn.lypi.contracts.model.TextDelta;
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
import static cn.lypi.agent.AgentCoreTestFixtures.assistantToolCallMessage;
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
    void retriesPromptTooLongByDroppingOldestApiRoundOnlyAfterFullContextAttempt() {
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        provider.failWith(new ContextOverflowException(
            "context_length_exceeded",
            ErrorSeverity.ERROR,
            false,
            "Prompt too long by 8 tokens."
        ));
        provider.enqueue(List.of(
            new TextDelta("summary after retry"),
            new AssistantDone(Optional.of(new TokenUsage(20, 3, 0, 0)), Optional.of("stop"))
        ));
        AiCompactionSummarizer summarizer = summarizer(provider, CompactionSummaryOptions.defaults());
        CompactSummaryRequest request = request(contextWithApiRounds(), () -> false);

        CompactSummaryResult result = summarizer.summarize(request);

        assertThat(result.summary()).isEqualTo("summary after retry");
        assertThat(provider.contexts).hasSize(2);
        assertThat(provider.contexts.getFirst().messages())
            .startsWith(contextWithApiRounds().messages().toArray(AgentMessage[]::new));
        assertThat(provider.contexts.getFirst().messages()).hasSize(contextWithApiRounds().messages().size() + 1);
        assertThat(provider.contexts.get(1).messages())
            .extracting(message -> message.content().getFirst().text())
            .doesNotContain("old user before first assistant", "old assistant");
        assertThat(provider.contexts.get(1).messages().getFirst().role()).isEqualTo(MessageRole.USER);
        assertThat(provider.contexts.get(1).messages().getFirst().content().getFirst().text())
            .isEqualTo("[earlier conversation truncated for compaction retry]");
        assertThat(provider.contexts.get(1).messages().getLast().content().getFirst().text())
            .contains("compact summary", "不要调用工具");
    }

    @Test
    void tokenGapRetryCountsToolCallInputMetadataWhenDroppingOldestApiRounds() {
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        provider.failWith(new ContextOverflowException(
            "context_length_exceeded",
            ErrorSeverity.ERROR,
            false,
            "Prompt too long by 100 tokens."
        ));
        provider.enqueue(List.of(
            new TextDelta("summary after tool metadata retry"),
            new AssistantDone(Optional.empty(), Optional.of("stop"))
        ));
        AiCompactionSummarizer summarizer = summarizer(provider, CompactionSummaryOptions.defaults());
        ContextSnapshot context = contextWithLargeToolCallInput();

        CompactSummaryResult result = summarizer.summarize(request(context, () -> false));

        assertThat(result.summary()).isEqualTo("summary after tool metadata retry");
        assertThat(provider.contexts).hasSize(2);
        assertThat(provider.contexts.get(1).messages())
            .extracting(AgentMessage::id)
            .doesNotContain("msg-old-user", "msg-old-tool")
            .contains("msg-middle-assistant", "msg-recent-assistant");
    }

    @Test
    void promptTooLongRetryTreatsNullBlockMetadataAsEmpty() {
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        provider.failWith(new ContextOverflowException(
            "context_length_exceeded",
            ErrorSeverity.ERROR,
            false,
            "Prompt too long by 20 tokens."
        ));
        provider.enqueue(List.of(
            new TextDelta("summary after null metadata retry"),
            new AssistantDone(Optional.empty(), Optional.of("stop"))
        ));
        AiCompactionSummarizer summarizer = summarizer(provider, CompactionSummaryOptions.defaults());
        ContextSnapshot context = contextWithNullToolCallMetadata();

        CompactSummaryResult result = summarizer.summarize(request(context, () -> false));

        assertThat(result.summary()).isEqualTo("summary after null metadata retry");
        assertThat(provider.contexts).hasSize(2);
        assertThat(provider.contexts.get(1).messages())
            .extracting(AgentMessage::id)
            .contains("msg-recent-assistant");
    }

    @Test
    void promptTooLongRetryTreatsNullFirstUserMetadataAsNonMarker() {
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        provider.failWith(new ContextOverflowException(
            "context_length_exceeded",
            ErrorSeverity.ERROR,
            false,
            "Prompt too long by 4 tokens."
        ));
        provider.enqueue(List.of(
            new TextDelta("summary after null first metadata retry"),
            new AssistantDone(Optional.empty(), Optional.of("stop"))
        ));
        AiCompactionSummarizer summarizer = summarizer(provider, CompactionSummaryOptions.defaults());
        ContextSnapshot context = contextWithNullFirstUserMetadata();

        CompactSummaryResult result = summarizer.summarize(request(context, () -> false));

        assertThat(result.summary()).isEqualTo("summary after null first metadata retry");
        assertThat(provider.contexts).hasSize(2);
        assertThat(provider.contexts.get(1).messages())
            .extracting(AgentMessage::id)
            .contains("msg-recent-assistant");
    }

    @Test
    void retriesWhenProviderSendsPromptTooLongAssistantError() {
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        provider.enqueue(List.of(new AssistantError(
            "context_length_exceeded",
            "Prompt too long by 8 tokens."
        )));
        provider.enqueue(List.of(
            new TextDelta("summary after assistant error retry"),
            new AssistantDone(Optional.empty(), Optional.of("stop"))
        ));
        AiCompactionSummarizer summarizer = summarizer(provider, CompactionSummaryOptions.defaults());

        CompactSummaryResult result = summarizer.summarize(request(contextWithApiRounds(), () -> false));

        assertThat(result.summary()).isEqualTo("summary after assistant error retry");
        assertThat(provider.contexts).hasSize(2);
        assertThat(provider.contexts.get(1).messages())
            .extracting(message -> message.content().getFirst().text())
            .doesNotContain("old user before first assistant", "old assistant");
    }

    @Test
    void retriesWhenProviderReturnsPromptTooLongText() {
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        provider.enqueue(List.of(
            new TextDelta("Prompt too long by 8 tokens."),
            new AssistantDone(Optional.empty(), Optional.of("stop"))
        ));
        provider.enqueue(List.of(
            new TextDelta("summary after text retry"),
            new AssistantDone(Optional.empty(), Optional.of("stop"))
        ));
        AiCompactionSummarizer summarizer = summarizer(provider, CompactionSummaryOptions.defaults());

        CompactSummaryResult result = summarizer.summarize(request(contextWithApiRounds(), () -> false));

        assertThat(result.summary()).isEqualTo("summary after text retry");
        assertThat(provider.contexts).hasSize(2);
        assertThat(provider.contexts.get(1).messages())
            .extracting(message -> message.content().getFirst().text())
            .doesNotContain("old user before first assistant", "old assistant");
    }

    @Test
    void stripsPreviousRetryMarkerBeforeTruncatingAgain() {
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        provider.failWith(new ContextOverflowException(
            "context_length_exceeded",
            ErrorSeverity.ERROR,
            false,
            "Prompt too long."
        ));
        provider.failWith(new ContextOverflowException(
            "context_length_exceeded",
            ErrorSeverity.ERROR,
            false,
            "Prompt too long."
        ));
        provider.enqueue(List.of(
            new TextDelta("summary after second retry"),
            new AssistantDone(Optional.empty(), Optional.of("stop"))
        ));
        AiCompactionSummarizer summarizer = summarizer(provider, CompactionSummaryOptions.defaults());

        CompactSummaryResult result = summarizer.summarize(request(contextWithApiRounds(), () -> false));

        assertThat(result.summary()).isEqualTo("summary after second retry");
        assertThat(provider.contexts).hasSize(3);
        assertThat(provider.contexts.get(2).messages())
            .filteredOn(message -> message.content().getFirst().text().equals("[earlier conversation truncated for compaction retry]"))
            .hasSize(1);
        assertThat(provider.contexts.get(2).messages())
            .extracting(message -> message.content().getFirst().text())
            .doesNotContain("old user before first assistant");
    }

    @Test
    void throwsPromptTooLongWhenRetryCannotLeaveASummarizableRound() {
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        provider.failWith(new ContextOverflowException(
            "context_length_exceeded",
            ErrorSeverity.ERROR,
            false,
            "Prompt too long."
        ));
        AiCompactionSummarizer summarizer = summarizer(provider, CompactionSummaryOptions.defaults());
        ContextSnapshot oneRoundContext = minimalContext(List.of(
            userMessage("msg-user-1", "only user"),
            userMessage("msg-user-2", "same preamble group")
        ));

        assertThatThrownBy(() -> summarizer.summarize(request(oneRoundContext, () -> false)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("prompt too long")
            .hasMessageContaining("AI compaction summary failed");
        assertThat(provider.contexts).hasSize(1);
    }

    @Test
    void throwsPromptTooLongAfterMaxRetryAttempts() {
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        for (int index = 0; index < 4; index++) {
            provider.failWith(new ContextOverflowException(
                "context_length_exceeded",
                ErrorSeverity.ERROR,
                false,
                "Prompt too long."
            ));
        }
        AiCompactionSummarizer summarizer = summarizer(provider, CompactionSummaryOptions.defaults());
        ContextSnapshot context = contextWithManyApiRounds();

        assertThatThrownBy(() -> summarizer.summarize(request(context, () -> false)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("AI compaction summary failed")
            .hasMessageContaining("prompt too long after 4 attempt(s)");
        assertThat(provider.contexts).hasSize(4);
        assertThat(provider.contexts.getFirst().messages())
            .startsWith(context.messages().toArray(AgentMessage[]::new));
    }

    @Test
    void throwsWhenProviderThrows() {
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        provider.failWith(new IllegalStateException("provider down"));
        AiCompactionSummarizer summarizer = summarizer(provider, CompactionSummaryOptions.defaults());

        assertThatThrownBy(() -> summarizer.summarize(request(() -> false)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("AI compaction summary failed")
            .hasMessageContaining("FALLBACK_DETERMINISTIC")
            .hasMessageContaining("provider down");
    }

    @Test
    void throwsWhenProviderReturnsEmptyText() {
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        provider.enqueue(List.of(new AssistantDone(Optional.empty(), Optional.of("stop"))));
        AiCompactionSummarizer summarizer = summarizer(provider, CompactionSummaryOptions.defaults());

        assertThatThrownBy(() -> summarizer.summarize(request(() -> false)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("AI compaction summary failed")
            .hasMessageContaining("FALLBACK_DETERMINISTIC")
            .hasMessageContaining("empty output");
    }

    @Test
    void throwsWhenProviderSendsAssistantError() {
        AgentCoreTestFixtures.StubAiProvider provider = new AgentCoreTestFixtures.StubAiProvider();
        provider.enqueue(List.of(new AssistantError("err-1", "bad stream")));
        AiCompactionSummarizer summarizer = summarizer(provider, CompactionSummaryOptions.defaults());

        assertThatThrownBy(() -> summarizer.summarize(request(() -> false)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("AI compaction summary failed")
            .hasMessageContaining("FALLBACK_DETERMINISTIC")
            .hasMessageContaining("bad stream");
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
            new CompactionSummaryOptions(CompactionSummaryFallbackPolicy.SKIP_COMPACTION)
        );

        assertThatThrownBy(() -> summarizer.summarize(request(() -> false)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SKIP_COMPACTION")
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
            options
        );
    }

    private static CompactSummaryRequest request(AbortSignal abortSignal) {
        return new CompactSummaryRequest(currentContext(), plan(), branchEntries(), abortSignal);
    }

    private static CompactSummaryRequest request(ContextSnapshot context, AbortSignal abortSignal) {
        return new CompactSummaryRequest(context, plan(), branchEntries(context), abortSignal);
    }

    private static ContextSnapshot currentContext() {
        return minimalContext(List.of(
            userMessage("msg-user-1", "Need compact"),
            assistantMessage("msg-assistant-1", "Working")
        ));
    }

    private static ContextSnapshot contextWithApiRounds() {
        return minimalContext(List.of(
            userMessage("msg-old-user", "old user before first assistant"),
            assistantMessage("msg-old-assistant", "old assistant"),
            assistantMessage("msg-middle-assistant", "middle assistant"),
            assistantMessage("msg-recent-assistant", "recent assistant")
        ));
    }

    private static ContextSnapshot contextWithManyApiRounds() {
        return minimalContext(List.of(
            userMessage("msg-old-user", "old user before first assistant"),
            assistantMessage("msg-assistant-1", "assistant one"),
            assistantMessage("msg-assistant-2", "assistant two"),
            assistantMessage("msg-assistant-3", "assistant three"),
            assistantMessage("msg-assistant-4", "assistant four"),
            assistantMessage("msg-assistant-5", "assistant five"),
            assistantMessage("msg-assistant-6", "assistant six")
        ));
    }

    private static ContextSnapshot contextWithLargeToolCallInput() {
        return minimalContext(List.of(
            userMessage("msg-old-user", "old user before first assistant"),
            assistantToolCallMessage(
                "msg-old-tool",
                "tool-call-1",
                "write",
                java.util.Map.of("payload", "x".repeat(420))
            ),
            assistantMessage("msg-middle-assistant", "middle assistant"),
            assistantMessage("msg-recent-assistant", "recent assistant")
        ));
    }

    private static ContextSnapshot contextWithNullToolCallMetadata() {
        return minimalContext(List.of(
            userMessage("msg-old-user", "old user before first assistant"),
            new AgentMessage(
                "msg-null-tool",
                MessageRole.ASSISTANT,
                cn.lypi.contracts.context.MessageKind.TOOL_CALL,
                List.of(new ToolCallContentBlock("tool-call-1", "read", "", null)),
                NOW,
                Optional.empty(),
                Optional.of("tool_calls")
            ),
            assistantMessage("msg-recent-assistant", "recent assistant")
        ));
    }

    private static ContextSnapshot contextWithNullFirstUserMetadata() {
        return minimalContext(List.of(
            new AgentMessage(
                "msg-old-user",
                MessageRole.USER,
                cn.lypi.contracts.context.MessageKind.TEXT,
                List.of(new TextContentBlock("old user before first assistant", null)),
                NOW,
                Optional.empty(),
                Optional.empty()
            ),
            assistantMessage("msg-old-assistant", "old assistant"),
            assistantMessage("msg-recent-assistant", "recent assistant")
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
        return branchEntries(currentContext());
    }

    private static List<SessionEntry> branchEntries(ContextSnapshot context) {
        List<AgentMessage> messages = context.messages();
        java.util.ArrayList<SessionEntry> entries = new java.util.ArrayList<>();
        String parentId = "";
        for (int index = 0; index < messages.size(); index++) {
            String entryId = "entry-" + index;
            entries.add(new MessageEntry(entryId, parentId, messages.get(index), NOW));
            parentId = entryId;
        }
        return entries;
    }
}
