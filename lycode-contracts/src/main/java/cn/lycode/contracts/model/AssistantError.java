package cn.lycode.contracts.model;

public record AssistantError(
    String errorId,
    String message
) implements AssistantStreamEvent {}

