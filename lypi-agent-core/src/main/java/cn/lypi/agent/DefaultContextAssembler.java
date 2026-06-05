package cn.lypi.agent;

import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.resource.ResourceSnapshot;
import cn.lypi.contracts.runtime.ResourceRuntimePort;
import cn.lypi.contracts.runtime.SessionEnginePort;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHandle;
import java.util.List;

public final class DefaultContextAssembler implements ContextAssembler {
    private final SessionEnginePort sessionEngine;
    private final ResourceRuntimePort resourceRuntime;
    private final ContextBudgetEstimator budgetEstimator;
    private final ContextEntryProjector projector;

    public DefaultContextAssembler(
        SessionEnginePort sessionEngine,
        ResourceRuntimePort resourceRuntime,
        ContextBudgetEstimator budgetEstimator
    ) {
        this.sessionEngine = sessionEngine;
        this.resourceRuntime = resourceRuntime;
        this.budgetEstimator = budgetEstimator;
        this.projector = new ContextEntryProjector();
    }

    @Override
    public ContextAssembly build(ContextBuildRequest request) {
        SessionHandle handle = sessionEngine.openOrCreate(request.sessionId());
        String leafId = request.leafEntryId().orElse(handle.leafId());
        List<SessionEntry> path = leafId == null || leafId.isBlank() ? List.of() : sessionEngine.pathToRoot(leafId);
        ResourceSnapshot resources = resourceRuntime.load(request.cwd());
        SystemPrompt systemPrompt = request.includeSystemPrompt() ? resourceRuntime.buildSystemPrompt(resources) : null;
        ContextEntryProjector.Projection projection = projector.project(path);
        ContextBudget budget = budgetEstimator.estimate(projection.messages());

        ContextSnapshot snapshot = new ContextSnapshot(
            systemPrompt,
            projection.messages(),
            projection.model(),
            projection.thinkingLevel(),
            projection.mode(),
            projection.permissionMode(),
            budget
        );

        return new ContextAssembly(
            snapshot,
            projection.branchEntryIds(),
            projection.appliedCompactionEntryIds(),
            List.of(),
            budget.estimatedContextTokens() > budget.autoCompactThreshold()
        );
    }
}
