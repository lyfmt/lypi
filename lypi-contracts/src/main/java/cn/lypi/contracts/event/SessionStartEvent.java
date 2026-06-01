package cn.lypi.contracts.event;

import java.time.Instant;

public record SessionStartEvent(
    String sessionId,
    Instant timestamp
) implements AgentEvent {}

