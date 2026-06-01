package cn.lypi.contracts.model;

public record AssistantError(
    String errorId,
    String message
) implements AssistantStreamEvent {}

