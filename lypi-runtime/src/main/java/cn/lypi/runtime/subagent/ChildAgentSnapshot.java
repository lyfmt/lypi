package cn.lypi.runtime.subagent;

import java.util.Optional;

public record ChildAgentSnapshot(
    String childSessionId,
    String parentSessionId,
    String parentSpawnEntryId,
    Optional<String> agentName,
    Optional<String> agentRole
) {
    public ChildAgentSnapshot {
        agentName = agentName == null ? Optional.empty() : agentName;
        agentRole = agentRole == null ? Optional.empty() : agentRole;
    }
}
