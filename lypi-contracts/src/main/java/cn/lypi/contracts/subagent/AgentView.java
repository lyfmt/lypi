package cn.lypi.contracts.subagent;

import java.util.Optional;

public record AgentView(
    String agentId,
    String label,
    String parentSessionId,
    String childSessionId,
    String parentSpawnEntryId,
    AgentRunStatus status,
    Optional<MailboxStatus> mailboxStatus,
    Optional<String> summary,
    Optional<String> finalEntryId,
    Optional<String> agentName,
    Optional<String> agentRole
) {
    public AgentView {
        mailboxStatus = mailboxStatus == null ? Optional.empty() : mailboxStatus;
        summary = summary == null ? Optional.empty() : summary;
        finalEntryId = finalEntryId == null ? Optional.empty() : finalEntryId;
        agentName = agentName == null ? Optional.empty() : agentName;
        agentRole = agentRole == null ? Optional.empty() : agentRole;
    }
}
