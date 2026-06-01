package cn.lypi.contracts.event;

import java.time.Instant;

public record ToolEndEvent(
    String sessionId,
    String toolUseId,
    boolean error,
    Instant timestamp
) implements AgentEvent {}

