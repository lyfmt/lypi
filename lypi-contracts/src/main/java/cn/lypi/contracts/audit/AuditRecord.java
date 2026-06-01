package cn.lypi.contracts.audit;

import java.util.Map;

public record AuditRecord(
    String auditId,
    String sessionId,
    String entryId,
    AuditKind kind,
    Map<String, Object> details
) {}

