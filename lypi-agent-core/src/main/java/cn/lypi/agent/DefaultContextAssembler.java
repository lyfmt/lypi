package cn.lypi.agent;

import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.resource.ResourceSnapshot;
import cn.lypi.contracts.runtime.ResourceRuntimePort;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.session.SessionContext;
import java.util.List;

public final class DefaultContextAssembler implements ContextAssembler {
    private final SessionManagerPort sessionManager;
    private final ResourceRuntimePort resourceRuntime;
    private final ContextBudgetEstimator budgetEstimator;

    public DefaultContextAssembler(
        SessionManagerPort sessionManager,
        ResourceRuntimePort resourceRuntime,
        ContextBudgetEstimator budgetEstimator
    ) {
        this.sessionManager = sessionManager;
        this.resourceRuntime = resourceRuntime;
        this.budgetEstimator = budgetEstimator;
    }

    @Override
    public ContextAssembly build(ContextBuildRequest request) {
        SessionHandle handle = sessionManager.openOrCreate(request.sessionId());
        String leafId = request.leafEntryId().orElse(handle.leafId());
        ResourceSnapshot resources = resourceRuntime.load(request.cwd());
        SystemPrompt systemPrompt = request.includeSystemPrompt() ? resourceRuntime.buildSystemPrompt(resources) : null;
        SessionContext sessionContext = sessionManager.context(leafId);
        ContextBudget budget = budgetEstimator.estimate(systemPrompt, sessionContext.messages());
        ContextSnapshot snapshot = new ContextSnapshot(
            systemPrompt,
            sessionContext.messages(),
            sessionContext.model(),
            sessionContext.thinkingLevel(),
            sessionContext.mode(),
            sessionContext.permissionMode(),
            budget
        );

        return new ContextAssembly(
            snapshot,
            sessionContext.branchEntryIds(),
            sessionContext.appliedCompactionEntryIds(),
            List.of(),
            budget.estimatedContextTokens() > budget.autoCompactThreshold()
        );
    }
}
