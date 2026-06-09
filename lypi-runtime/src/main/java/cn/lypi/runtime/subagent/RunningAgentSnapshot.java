package cn.lypi.runtime.subagent;

import java.util.Optional;

public record RunningAgentSnapshot(
    String agentId,
    String childSessionId,
    String parentSessionId,
    String parentSpawnEntryId,
    Optional<String> agentName,
    Optional<String> agentRole
) {
    public RunningAgentSnapshot {
        agentName = agentName == null ? Optional.empty() : agentName;
        agentRole = agentRole == null ? Optional.empty() : agentRole;
    }
}
