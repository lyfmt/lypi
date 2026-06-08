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
    Optional<String> parentSpawnEntryId,
    int depth,
    Optional<String> agentName,
    Optional<String> agentRole,
    Instant timestamp
) {
    public SessionHeader {
        parentSessionId = parentSessionId == null ? Optional.empty() : parentSessionId;
        parentSpawnEntryId = parentSpawnEntryId == null ? Optional.empty() : parentSpawnEntryId;
        agentName = agentName == null ? Optional.empty() : agentName;
        agentRole = agentRole == null ? Optional.empty() : agentRole;
    }

    public SessionHeader(
        String type,
        int version,
        String id,
        Path cwd,
        Optional<String> parentSessionId,
        Instant timestamp
    ) {
        this(type, version, id, cwd, parentSessionId, Optional.empty(), 0, Optional.empty(), Optional.empty(), timestamp);
    }
}
