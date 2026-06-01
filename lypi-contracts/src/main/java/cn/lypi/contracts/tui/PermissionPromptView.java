package cn.lypi.contracts.tui;

public record PermissionPromptView(
    String toolUseId,
    String reason,
    String rule
) {}

