package cn.lypi.contracts.audit;

import java.util.Map;
import java.util.Optional;

public record AuditRecord(
    String auditId,
    String sessionId,
    String entryId,
    AuditKind kind,
    Optional<String> toolUseId,
    Optional<String> messageId,
    Map<String, Object> details
) {
    public AuditRecord {
        toolUseId = toolUseId == null ? Optional.empty() : toolUseId;
        messageId = messageId == null ? Optional.empty() : messageId;
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public AuditRecord(
        String auditId,
        String sessionId,
        String entryId,
        AuditKind kind,
        Map<String, Object> details
    ) {
        this(
            auditId,
            sessionId,
            entryId,
            kind,
            stringDetail(details, "toolUseId"),
            stringDetail(details, "messageId"),
            details
        );
    }

    private static Optional<String> stringDetail(Map<String, Object> details, String key) {
        if (details == null) {
            return Optional.empty();
        }
        Object value = details.get(key);
        return value instanceof String stringValue ? Optional.of(stringValue) : Optional.empty();
    }
}
