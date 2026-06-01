package cn.lypi.contracts.event;

import java.time.Instant;

public record InterruptEvent(
    String sessionId,
    String reason,
    Instant timestamp
) implements AgentEvent {}

