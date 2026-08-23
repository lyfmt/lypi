package cn.lycode.contracts.event;

import java.time.Instant;

public record ProviderFallbackEndEvent(
    String sessionId,
    String toMode,
    boolean success,
    Instant timestamp
) implements AgentEvent {}
