package cn.lypi.contracts.event;

import java.time.Instant;

public record TurnEndEvent(
    String sessionId,
    String turnId,
    String status,
    Instant timestamp
) implements AgentEvent {}

