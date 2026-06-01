package cn.lypi.contracts.event;

import java.time.Instant;

public record ToolProgressEvent(
    String sessionId,
    String toolUseId,
    String message,
    Instant timestamp
) implements AgentEvent {}

