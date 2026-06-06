package cn.lypi.agent;

import cn.lypi.agent.compact.CompactSummaryRequest;
import cn.lypi.agent.compact.CompactSummaryResult;
import cn.lypi.agent.compact.CompactionSummaryFallbackPolicy;
import cn.lypi.agent.compact.CompactionSummaryOptions;
import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.model.TokenUsage;
import cn.lypi.contracts.session.CompactionKind;
import cn.lypi.contracts.session.CompactionPlan;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.SessionEntry;
import java.util.List;
import org.junit.jupiter.api.Test;

import static cn.lypi.agent.AgentCoreTestFixtures.NOW;
import static cn.lypi.agent.AgentCoreTestFixtures.minimalContext;
import static cn.lypi.agent.AgentCoreTestFixtures.userMessage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class CompactSummaryContractTest {
    @Test
    void summaryContractCarriesContextPlanEntriesAbortResultAndOptions() {
        ContextSnapshot currentContext = minimalContext(List.of(userMessage("msg-user", "hello")));
        CompactionPlan plan = new CompactionPlan(
            "entry-user",
            "entry-kept",
            List.of("entry-user"),
            CompactionKind.SESSION
        );
        List<SessionEntry> branchEntries = List.of(new MessageEntry("entry-user", "", userMessage("msg-user", "hello"), NOW));
        AbortSignal abortSignal = () -> true;

        CompactSummaryRequest request = new CompactSummaryRequest(currentContext, plan, branchEntries, abortSignal);
        CompactSummaryResult result = new CompactSummaryResult("summary", new TokenUsage(10, 2, 8, 0));
        CompactionSummaryOptions options = CompactionSummaryOptions.defaults();

        assertThat(request.context()).isSameAs(currentContext);
        assertThat(request.plan()).isSameAs(plan);
        assertThat(request.branchEntries()).containsExactlyElementsOf(branchEntries);
        assertThat(request.abortSignal()).isSameAs(abortSignal);
        assertThat(result.summary()).isEqualTo("summary");
        assertThat(result.usage().inputTokens()).isEqualTo(10);
        assertThat(options.fallbackPolicy()).isEqualTo(CompactionSummaryFallbackPolicy.FALLBACK_DETERMINISTIC);
        assertThat(options.thinkingLevel()).isEqualTo(ThinkingLevel.OFF);
    }

    @Test
    void summaryRequestRejectsNullRequiredFields() {
        ContextSnapshot currentContext = minimalContext(List.of(userMessage("msg-user", "hello")));
        CompactionPlan plan = new CompactionPlan(
            "entry-user",
            "entry-kept",
            List.of("entry-user"),
            CompactionKind.SESSION
        );
        List<SessionEntry> branchEntries = List.of(new MessageEntry("entry-user", "", userMessage("msg-user", "hello"), NOW));

        assertThatNullPointerException()
            .isThrownBy(() -> new CompactSummaryRequest(null, plan, branchEntries, () -> false))
            .withMessageContaining("context");
        assertThatNullPointerException()
            .isThrownBy(() -> new CompactSummaryRequest(currentContext, null, branchEntries, () -> false))
            .withMessageContaining("plan");
        assertThatNullPointerException()
            .isThrownBy(() -> new CompactSummaryRequest(currentContext, plan, null, () -> false))
            .withMessageContaining("branchEntries");
        assertThatNullPointerException()
            .isThrownBy(() -> new CompactSummaryRequest(currentContext, plan, branchEntries, null))
            .withMessageContaining("abortSignal");
    }

    @Test
    void summaryResultRejectsBlankSummaryAndNullUsage() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new CompactSummaryResult(" ", new TokenUsage(0, 0, 0, 0)))
            .withMessageContaining("summary");
        assertThatNullPointerException()
            .isThrownBy(() -> new CompactSummaryResult("summary", null))
            .withMessageContaining("usage");
    }
}
