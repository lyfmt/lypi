package cn.lypi.contracts.event;

import java.time.Instant;

public record ProviderFallbackStartEvent(
    String sessionId,
    String fromMode,
    String toMode,
    String reason,
    Instant timestamp
) implements AgentEvent {}
