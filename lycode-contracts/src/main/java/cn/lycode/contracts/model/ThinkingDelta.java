package cn.lycode.contracts.model;

public record ThinkingDelta(
    String text
) implements AssistantStreamEvent {}

