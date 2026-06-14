package cn.lypi.contracts.subagent;

import java.util.Optional;

public record SubagentSpawnResult(
    String agentId,
    String childSessionId,
    String parentSessionId,
    String parentSpawnEntryId,
    SubagentRunStatus status,
    Optional<String> message
) {
    public SubagentSpawnResult {
        message = message == null ? Optional.empty() : message;
    }
}
