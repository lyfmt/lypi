package cn.lypi.contracts.event;

import java.time.Instant;

public record CompactStartEvent(
    String sessionId,
    String kind,
    Instant timestamp
) implements AgentEvent {}

