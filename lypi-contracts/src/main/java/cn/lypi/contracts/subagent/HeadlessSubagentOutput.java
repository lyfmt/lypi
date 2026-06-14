package cn.lypi.contracts.subagent;

import java.util.Optional;

public record HeadlessSubagentOutput(
    String childSessionId,
    SubagentRunStatus status,
    String summary,
    Optional<String> finalEntryId,
    Optional<String> errorMessage
) {
    public HeadlessSubagentOutput {
        finalEntryId = finalEntryId == null ? Optional.empty() : finalEntryId;
        errorMessage = errorMessage == null ? Optional.empty() : errorMessage;
    }
}
