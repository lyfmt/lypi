package cn.lypi.session;

import java.nio.file.Path;
import java.util.Optional;

public record ChildSessionView(
    String sessionId,
    Path cwd,
    Optional<String> parentSessionId,
    Optional<String> parentSpawnEntryId,
    int depth,
    Optional<String> agentName,
    Optional<String> agentRole
) {}
