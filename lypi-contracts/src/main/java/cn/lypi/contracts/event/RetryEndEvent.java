package cn.lypi.contracts.event;

import java.time.Instant;

public record RetryEndEvent(
    String sessionId,
    int attempt,
    boolean success,
    Instant timestamp
) implements AgentEvent {}

