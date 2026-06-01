package cn.lypi.contracts.model;

public record TextDelta(
    String text
) implements AssistantStreamEvent {}

