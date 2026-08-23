package cn.lycode.contracts.context;

import java.math.BigDecimal;

public record ContextBudget(
    int estimatedContextTokens,
    int effectiveContextWindow,
    int autoCompactThreshold,
    int turnOutputBudget,
    int toolResultBudget,
    long totalInputTokens,
    long totalOutputTokens,
    BigDecimal estimatedCost
) {}

