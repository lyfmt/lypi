package cn.lycode.contracts.model;

public record AssistantStart(
    String messageId
) implements AssistantStreamEvent {}

