package cn.lypi.contracts.event;

import java.time.Instant;

public record PermissionRequestEvent(
    String sessionId,
    String toolUseId,
    String message,
    Instant timestamp
) implements AgentEvent {}

