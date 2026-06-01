package cn.lypi.contracts.memory;

import java.nio.file.Path;

public record MemoryWriteRequest(
    MemoryCandidate candidate,
    Path targetPath,
    String mergeKey
) {}

