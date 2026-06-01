package cn.lypi.contracts.session;

import java.nio.file.Path;
import java.time.Instant;

public record FileChangeEntry(
    String id,
    String parentId,
    Path path,
    FileOperation operation,
    String beforeHash,
    String afterHash,
    String diff,
    String toolUseId,
    String messageId,
    Instant timestamp
) implements SessionEntry {}

