package cn.lypi.contracts.event;

import java.time.Instant;

public record RetryStartEvent(
    String sessionId,
    int attempt,
    String reason,
    Instant timestamp
) implements AgentEvent {}

