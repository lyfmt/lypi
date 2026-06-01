package cn.lypi.contracts.common;

import java.util.List;

public record ValidationResult(
    boolean valid,
    List<String> messages
) {}

