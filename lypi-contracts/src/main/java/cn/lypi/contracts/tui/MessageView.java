package cn.lypi.contracts.tui;

public record MessageView(
    String messageId,
    String role,
    String content
) {}

