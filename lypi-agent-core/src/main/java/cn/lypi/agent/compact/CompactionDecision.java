package cn.lypi.agent.compact;

import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.session.CompactionPlan;
import java.util.Optional;

public record CompactionDecision(
    ContextSnapshot context,
    Optional<CompactionPlan> plan,
    boolean compacted,
    String reason
) {}
