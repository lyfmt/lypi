package cn.lypi.contracts.session;

import java.nio.file.Path;
import java.util.Optional;

public record ChildSessionRequest(
    String childSessionId,
    String parentSessionId,
    String parentSpawnEntryId,
    Path cwd,
    int depth,
    Optional<String> agentName,
    Optional<String> agentRole
) {
    public ChildSessionRequest {
        agentName = agentName == null ? Optional.empty() : agentName;
        agentRole = agentRole == null ? Optional.empty() : agentRole;
    }
}
