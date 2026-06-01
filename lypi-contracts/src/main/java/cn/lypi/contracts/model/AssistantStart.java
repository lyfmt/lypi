package cn.lypi.contracts.model;

public record AssistantStart(
    String messageId
) implements AssistantStreamEvent {}

