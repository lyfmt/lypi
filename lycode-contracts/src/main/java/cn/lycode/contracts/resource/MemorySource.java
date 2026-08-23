package cn.lycode.contracts.resource;

import cn.lycode.contracts.memory.MemoryScope;
import java.nio.file.Path;

public record MemorySource(
    MemoryScope scope,
    Path path,
    String content,
    String contentHash
) {}
