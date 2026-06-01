package cn.lypi.contracts.model;

public record TokenUsage(
    long inputTokens,
    long outputTokens,
    long cachedInputTokens,
    long reasoningTokens
) {}

