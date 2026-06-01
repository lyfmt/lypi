package cn.lypi.contracts.event;

import java.time.Instant;

public record TurnStartEvent(
    String sessionId,
    String turnId,
    Instant timestamp
) implements AgentEvent {}

