package cn.lypi.contracts.event;

import cn.lypi.contracts.security.PermissionDecision;
import java.time.Instant;

public record PermissionDecisionEvent(
    String sessionId,
    String toolUseId,
    PermissionDecision decision,
    Instant timestamp
) implements AgentEvent {}

