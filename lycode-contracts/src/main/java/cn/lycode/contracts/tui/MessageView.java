package cn.lycode.contracts.tui;

public record MessageView(
    String messageId,
    String role,
    String content
) {}

