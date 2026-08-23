package cn.lycode.contracts.model;

public record TextDelta(
    String text
) implements AssistantStreamEvent {}

