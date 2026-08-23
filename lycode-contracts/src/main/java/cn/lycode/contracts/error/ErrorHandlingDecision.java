package cn.lycode.contracts.error;

public record ErrorHandlingDecision(
    ErrorAction action,
    String userMessage,
    boolean appendToTranscript
) {}

