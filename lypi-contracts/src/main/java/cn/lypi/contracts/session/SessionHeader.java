package cn.lypi.contracts.session;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

public record SessionHeader(
    String type,
    int version,
    String id,
    Path cwd,
    Optional<String> parentSessionId,
    Instant timestamp
) {}

