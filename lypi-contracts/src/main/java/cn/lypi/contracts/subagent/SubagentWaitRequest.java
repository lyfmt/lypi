package cn.lypi.contracts.subagent;

import java.util.Optional;

public record SubagentWaitRequest(
    Optional<String> agentId,
    Optional<String> childSessionId,
    Optional<String> runId,
    int timeoutSeconds,
    boolean returnCompletedResult
) {
    public SubagentWaitRequest {
        agentId = agentId == null ? Optional.empty() : agentId;
        childSessionId = childSessionId == null ? Optional.empty() : childSessionId;
        runId = runId == null ? Optional.empty() : runId;
        timeoutSeconds = timeoutSeconds <= 0 ? 600 : timeoutSeconds;
    }

    public SubagentWaitRequest(Optional<String> agentId, Optional<String> childSessionId, int timeoutSeconds) {
        this(agentId, childSessionId, Optional.empty(), timeoutSeconds, true);
    }
}
