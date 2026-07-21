package cn.lypi.contracts.subagent;

import java.util.Optional;

public record HeadlessSubagentOutput(
    String taskName,
    String agentId,
    String childSessionId,
    String runId,
    SubagentRunStatus status,
    String content,
    Optional<String> finalEntryId,
    Optional<String> errorMessage
) {
    public HeadlessSubagentOutput {
        content = content == null ? "" : content;
        finalEntryId = finalEntryId == null ? Optional.empty() : finalEntryId;
        errorMessage = errorMessage == null ? Optional.empty() : errorMessage;
    }
}
