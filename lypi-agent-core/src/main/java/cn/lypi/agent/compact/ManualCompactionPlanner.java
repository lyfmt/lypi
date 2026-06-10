package cn.lypi.agent.compact;

import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.session.CompactionKind;
import cn.lypi.contracts.session.CompactionPlan;
import cn.lypi.contracts.session.SessionEntry;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public final class ManualCompactionPlanner implements CompactionPlanner {
    private final DefaultCompactionPlanner delegate;

    public ManualCompactionPlanner() {
        this.delegate = new DefaultCompactionPlanner();
    }

    public ManualCompactionPlanner(int keepRecentTokens) {
        this.delegate = new DefaultCompactionPlanner(keepRecentTokens);
    }

    @Override
    public Optional<CompactionPlan> plan(List<SessionEntry> branchEntries, ContextSnapshot context) {
        return delegate.plan(branchEntries, forceOverBudget(context))
            .map(plan -> new CompactionPlan(
                plan.cutEntryId(),
                plan.firstKeptEntryId(),
                plan.summarizedEntryIds(),
                CompactionKind.MANUAL
            ));
    }

    private ContextSnapshot forceOverBudget(ContextSnapshot context) {
        ContextBudget original = context.budget();
        ContextBudget budget = new ContextBudget(
            Math.max(original.estimatedContextTokens(), original.autoCompactThreshold() + 1),
            original.effectiveContextWindow(),
            original.autoCompactThreshold(),
            original.turnOutputBudget(),
            original.toolResultBudget(),
            original.totalInputTokens(),
            original.totalOutputTokens(),
            original.estimatedCost() == null ? BigDecimal.ZERO : original.estimatedCost()
        );
        return new ContextSnapshot(
            context.systemPrompt(),
            context.messages(),
            context.model(),
            context.thinkingLevel(),
            context.mode(),
            context.permissionMode(),
            budget
        );
    }
}
