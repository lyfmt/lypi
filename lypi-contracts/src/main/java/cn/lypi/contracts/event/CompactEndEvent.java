package cn.lypi.contracts.event;

import java.time.Instant;

public record CompactEndEvent(
    String sessionId,
    String compactionEntryId,
    Instant timestamp
) implements AgentEvent {}

