package cn.lypi.contracts.tui;

public record StatusBarState(
    String sessionId,
    String model,
    String mode,
    String permissionMode
) {}

