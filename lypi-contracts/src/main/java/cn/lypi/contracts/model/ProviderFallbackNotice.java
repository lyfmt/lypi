package cn.lypi.contracts.model;

public record ProviderFallbackNotice(
    String provider,
    int fromAttempt,
    int toAttempt,
    String fromMode,
    String toMode,
    String reason,
    String errorId,
    String message
) implements AssistantStreamEvent {}
