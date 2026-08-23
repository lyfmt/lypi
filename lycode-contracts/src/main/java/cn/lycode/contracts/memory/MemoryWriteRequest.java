package cn.lycode.contracts.memory;

import java.nio.file.Path;

public record MemoryWriteRequest(
    MemoryCandidate candidate,
    Path targetPath,
    String mergeKey
) {}

