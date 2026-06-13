package cn.lypi.agent.compact;

import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.session.CompactionPlan;
import java.util.Optional;

public record CompactionDecision(
    ContextSnapshot context,
    Optional<CompactionPlan> plan,
    boolean compacted,
    String reason,
    Optional<String> compactionEntryId
) {
    public CompactionDecision {
        plan = plan == null ? Optional.empty() : plan;
        reason = reason == null ? "" : reason;
        compactionEntryId = compactionEntryId == null ? Optional.empty() : compactionEntryId;
    }

    public CompactionDecision(
        ContextSnapshot context,
        Optional<CompactionPlan> plan,
        boolean compacted,
        String reason
    ) {
        this(context, plan, compacted, reason, Optional.empty());
    }
}
