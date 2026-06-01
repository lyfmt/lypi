package cn.lypi.contracts.boundary;

import java.util.List;

public record BoundaryCheckReport(
    List<BoundaryRuleResult> results,
    boolean passed
) {
    public BoundaryCheckReport(List<BoundaryRuleResult> results) {
        this(results, results.stream().allMatch(BoundaryRuleResult::passed));
    }
}
