package cn.lypi.contracts.resource;

import java.nio.file.Path;

public record MemorySource(
    Path path,
    String contentHash
) {}

