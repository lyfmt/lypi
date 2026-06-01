package cn.lypi.contracts.model;

public record ThinkingDelta(
    String text
) implements AssistantStreamEvent {}

