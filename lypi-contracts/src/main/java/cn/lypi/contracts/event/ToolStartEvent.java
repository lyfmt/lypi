package cn.lypi.contracts.event;

import java.time.Instant;

public record ToolStartEvent(
    String sessionId,
    String toolUseId,
    String toolName,
    Instant timestamp
) implements AgentEvent {}

