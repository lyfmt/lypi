package cn.lypi.agent.branch;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.session.BranchSummaryPlan;
import java.util.Objects;

/**
 * 表示一次 branch summary 生成请求。
 */
public record BranchSummaryRequest(
    ContextSnapshot context,
    BranchSummaryPlan plan,
    AbortSignal abortSignal
) {
    public BranchSummaryRequest {
        context = Objects.requireNonNull(context, "context must not be null");
        plan = Objects.requireNonNull(plan, "plan must not be null");
        abortSignal = abortSignal == null ? () -> false : abortSignal;
    }
}
