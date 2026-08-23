package cn.lycode.contracts.boundary;

public record BoundaryRuleResult(
    String ruleId,
    boolean passed,
    String evidence
) {}
