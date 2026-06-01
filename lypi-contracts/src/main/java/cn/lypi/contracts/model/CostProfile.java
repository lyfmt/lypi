package cn.lypi.contracts.model;

import java.math.BigDecimal;

public record CostProfile(
    BigDecimal inputTokenCost,
    BigDecimal outputTokenCost,
    String currency
) {}

