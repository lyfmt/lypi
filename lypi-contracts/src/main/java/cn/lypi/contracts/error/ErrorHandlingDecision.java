package cn.lypi.contracts.error;

public record ErrorHandlingDecision(
    ErrorAction action,
    String userMessage,
    boolean appendToTranscript
) {}

