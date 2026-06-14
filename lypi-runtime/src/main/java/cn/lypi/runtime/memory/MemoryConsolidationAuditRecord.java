package cn.lypi.runtime.memory;

import java.time.Instant;

/**
 * 记录一次后台记忆沉淀生命周期审计事件。
 */
public record MemoryConsolidationAuditRecord(
    MemoryConsolidationAuditStage stage,
    String sessionId,
    String turnId,
    String forkPointEntryId,
    String forkSessionId,
    long durationMillis,
    int toolRounds,
    String reason,
    String error,
    Instant timestamp
) {
    public MemoryConsolidationAuditRecord {
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }
}
