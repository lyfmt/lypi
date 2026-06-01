package cn.lypi.contracts.event;

import java.util.Optional;

public record EventFilter(
    Optional<String> sessionId,
    Optional<Class<? extends AgentEvent>> eventType
) {}

