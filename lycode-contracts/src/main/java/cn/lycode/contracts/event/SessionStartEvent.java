package cn.lycode.contracts.event;

import java.time.Instant;

public record SessionStartEvent(
    String sessionId,
    Instant timestamp
) implements AgentEvent {}

