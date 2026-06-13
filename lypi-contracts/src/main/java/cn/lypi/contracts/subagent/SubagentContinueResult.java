package cn.lypi.contracts.subagent;

import java.util.Optional;

public record SubagentContinueResult(
    String agentId,
    String childSessionId,
    String parentSessionId,
    String parentContinueEntryId,
    String runId,
    SubagentRunStatus status,
    Optional<String> message
) {
    public SubagentContinueResult {
        message = message == null ? Optional.empty() : message;
    }

    public SubagentContinueResult(
        String agentId,
        String childSessionId,
        String runId,
        SubagentRunStatus status,
        Optional<String> parentContinueEntryId
    ) {
        this(
            agentId,
            childSessionId,
            null,
            parentContinueEntryId == null ? null : parentContinueEntryId.orElse(null),
            runId,
            status,
            Optional.empty()
        );
    }
}
