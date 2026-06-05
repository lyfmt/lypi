package cn.lypi.agent;

import cn.lypi.contracts.context.ContextSnapshot;
import java.util.Optional;

public final class NoopCompactionCoordinator implements CompactionCoordinator {
    @Override
    public CompactionDecision preflight(ContextSnapshot context) {
        boolean exceeded = context.budget().estimatedContextTokens() > context.budget().autoCompactThreshold();
        return new CompactionDecision(
            context,
            Optional.empty(),
            false,
            exceeded ? "budget exceeded; compaction not implemented" : "within budget"
        );
    }
}
