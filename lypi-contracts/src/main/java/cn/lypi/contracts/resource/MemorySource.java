package cn.lypi.contracts.resource;

import cn.lypi.contracts.memory.MemoryScope;
import java.nio.file.Path;

public record MemorySource(
    MemoryScope scope,
    Path path,
    String content,
    String contentHash
) {}
