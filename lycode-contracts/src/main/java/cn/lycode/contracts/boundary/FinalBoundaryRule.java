package cn.lycode.contracts.boundary;

import java.util.List;

public record FinalBoundaryRule(
    String id,
    String description,
    BoundaryRuleLevel level,
    List<String> relatedParts
) {}
