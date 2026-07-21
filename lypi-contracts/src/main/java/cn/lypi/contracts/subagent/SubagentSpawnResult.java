package cn.lypi.contracts.subagent;

import java.util.Optional;

public record SubagentSpawnResult(
    String taskName,
    String agentId,
    String childSessionId,
    String runId,
    SubagentRunStatus status,
    Optional<String> message
) {
    public SubagentSpawnResult {
        message = message == null ? Optional.empty() : message;
    }
}
