package cn.lypi.contracts.subagent;

import java.time.Instant;
import java.util.Optional;

public record MailboxMessage(
    String mailId,
    String taskName,
    String agentId,
    String childSessionId,
    String runId,
    String parentSessionId,
    String parentSpawnEntryId,
    SubagentRunStatus runStatus,
    String content,
    Optional<String> finalEntryId,
    Optional<String> errorMessage,
    MailboxStatus status,
    Instant createdAt,
    Instant updatedAt
) {
    public MailboxMessage {
        content = content == null ? "" : content;
        finalEntryId = finalEntryId == null ? Optional.empty() : finalEntryId;
        errorMessage = errorMessage == null ? Optional.empty() : errorMessage;
    }
}
