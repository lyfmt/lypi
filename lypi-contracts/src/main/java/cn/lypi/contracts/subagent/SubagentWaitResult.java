package cn.lypi.contracts.subagent;

import java.util.Optional;

public record SubagentWaitResult(
    String agentId,
    String childSessionId,
    String runId,
    SubagentRunStatus status,
    Optional<String> summary,
    Optional<String> finalEntryId,
    Optional<String> errorMessage
) {
    public SubagentWaitResult {
        summary = summary == null ? Optional.empty() : summary;
        finalEntryId = finalEntryId == null ? Optional.empty() : finalEntryId;
        errorMessage = errorMessage == null ? Optional.empty() : errorMessage;
    }
}
