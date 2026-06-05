package cn.lypi.contracts.audit;

import java.util.Optional;

public record AuditQuery(
    Optional<String> sessionId,
    Optional<String> entryId,
    Optional<String> toolUseId,
    Optional<String> messageId,
    Optional<AuditKind> kind
) {
    public AuditQuery {
        sessionId = sessionId == null ? Optional.empty() : sessionId;
        entryId = entryId == null ? Optional.empty() : entryId;
        toolUseId = toolUseId == null ? Optional.empty() : toolUseId;
        messageId = messageId == null ? Optional.empty() : messageId;
        kind = kind == null ? Optional.empty() : kind;
    }
}
