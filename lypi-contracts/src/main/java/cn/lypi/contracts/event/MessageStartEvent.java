package cn.lypi.contracts.event;

import java.time.Instant;

public record MessageStartEvent(
    String sessionId,
    String messageId,
    Instant timestamp
) implements AgentEvent {}

