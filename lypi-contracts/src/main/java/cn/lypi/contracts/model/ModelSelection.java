package cn.lypi.contracts.model;

public record ModelSelection(
    String provider,
    String modelId,
    ThinkingLevel thinkingLevel
) {}

