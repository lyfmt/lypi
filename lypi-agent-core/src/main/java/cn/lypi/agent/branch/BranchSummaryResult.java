package cn.lypi.agent.branch;

import cn.lypi.contracts.model.TokenUsage;

/**
 * 表示 branch summary 生成结果。
 */
public record BranchSummaryResult(
    String summary,
    TokenUsage usage
) {
    public BranchSummaryResult {
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("summary must not be blank");
        }
        usage = usage == null ? new TokenUsage(0, 0, 0, 0) : usage;
    }
}
