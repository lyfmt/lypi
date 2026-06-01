package cn.lypi.contracts.resource;

import java.nio.file.Path;

public record ContextFile(
    Path path,
    String content,
    String contentHash
) {}

