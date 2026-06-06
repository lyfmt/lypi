package cn.lypi.agent.compact;

import java.util.Optional;

public final class NoopCompactionCoordinator implements CompactionCoordinator {
    @Override
    public CompactionDecision preflight(CompactionRequest request) {
        var context = request.assembly().snapshot();
        boolean exceeded = context.budget().estimatedContextTokens() > context.budget().autoCompactThreshold();
        return new CompactionDecision(
            context,
            Optional.empty(),
            false,
            exceeded ? "budget exceeded; compaction not implemented" : "within budget"
        );
    }
}
