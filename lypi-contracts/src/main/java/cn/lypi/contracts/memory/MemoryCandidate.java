package cn.lypi.contracts.memory;

import java.time.Instant;

public record MemoryCandidate(
    String content,
    MemoryScope scope,
    MemoryKind kind,
    String sourceMessageId,
    double confidence,
    Instant observedAt
) {}

