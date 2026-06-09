package cn.lypi.contracts.session;

import java.nio.file.Path;
import java.util.Optional;

public record ChildSessionRequest(
    String childSessionId,
    String parentSessionId,
    String parentSpawnEntryId,
    Path sessionCwd,
    Path cwd,
    int depth,
    Optional<String> agentName,
    Optional<String> agentRole
) {
    public ChildSessionRequest(
        String childSessionId,
        String parentSessionId,
        String parentSpawnEntryId,
        Path cwd,
        int depth,
        Optional<String> agentName,
        Optional<String> agentRole
    ) {
        this(childSessionId, parentSessionId, parentSpawnEntryId, cwd, cwd, depth, agentName, agentRole);
    }

    public ChildSessionRequest {
        sessionCwd = sessionCwd == null ? cwd : sessionCwd;
        cwd = cwd == null ? sessionCwd : cwd;
        agentName = agentName == null ? Optional.empty() : agentName;
        agentRole = agentRole == null ? Optional.empty() : agentRole;
    }
}
