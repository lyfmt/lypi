package cn.lypi.contracts.subagent;

import java.time.Instant;

public record MailboxMessage(
    String mailId,
    String agentId,
    String childSessionId,
    String parentSessionId,
    String parentSpawnEntryId,
    String summary,
    SubagentResultRef contentRef,
    MailboxStatus status,
    Instant createdAt,
    Instant updatedAt
) {}
