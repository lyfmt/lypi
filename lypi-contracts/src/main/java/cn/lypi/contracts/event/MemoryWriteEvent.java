package cn.lypi.contracts.event;

import java.nio.file.Path;
import java.time.Instant;

public record MemoryWriteEvent(
    String sessionId,
    Path targetPath,
    Instant timestamp
) implements AgentEvent {}

