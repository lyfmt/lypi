package cn.lypi.contracts.event;

import cn.lypi.contracts.security.PermissionDecision;
import java.time.Instant;

public record PermissionRequestEvent(
    String sessionId,
    String toolUseId,
    String toolName,
    String renderedToolUse,
    String message,
    PermissionDecision decision,
    Instant timestamp
) implements AgentEvent {}
