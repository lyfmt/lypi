package cn.lycode.contracts.model;

public record ModelSelection(
    String provider,
    String modelId,
    ThinkingLevel thinkingLevel
) {}

