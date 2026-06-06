package cn.lypi.contracts.memory;

import java.nio.file.Path;
import java.time.Instant;

public record MemoryWriteEntry(
    String id,
    String parentId,
    MemoryScope scope,
    Path targetPath,
    String contentHash,
    String sourceMessageId,
    Instant timestamp
) {}
