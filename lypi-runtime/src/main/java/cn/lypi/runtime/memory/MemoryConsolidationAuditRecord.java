package cn.lypi.runtime.memory;

import java.time.Instant;
import java.util.List;

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
    Instant timestamp,
    List<String> writtenPaths,
    List<String> lintDiagnostics,
    boolean coalesced
) {
    public MemoryConsolidationAuditRecord {
        timestamp = timestamp == null ? Instant.now() : timestamp;
        writtenPaths = writtenPaths == null ? List.of() : List.copyOf(writtenPaths);
        lintDiagnostics = lintDiagnostics == null ? List.of() : List.copyOf(lintDiagnostics);
    }

    public MemoryConsolidationAuditRecord(
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
        this(
            stage,
            sessionId,
            turnId,
            forkPointEntryId,
            forkSessionId,
            durationMillis,
            toolRounds,
            reason,
            error,
            timestamp,
            List.of(),
            List.of(),
            false
        );
    }
}
