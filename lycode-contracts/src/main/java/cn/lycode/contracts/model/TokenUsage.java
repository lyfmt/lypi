package cn.lycode.contracts.model;

public record TokenUsage(
    long inputTokens,
    long outputTokens,
    long cachedInputTokens,
    long reasoningTokens
) {}

