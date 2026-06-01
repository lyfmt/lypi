package cn.lypi.contracts.event;

import java.time.Instant;

public record MessageDeltaEvent(
    String sessionId,
    String messageId,
    String delta,
    Instant timestamp
) implements AgentEvent {}

