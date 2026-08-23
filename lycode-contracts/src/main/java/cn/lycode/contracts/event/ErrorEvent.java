package cn.lycode.contracts.event;

import java.time.Instant;

public record ErrorEvent(
    String sessionId,
    String errorId,
    String message,
    Instant timestamp
) implements AgentEvent {}

