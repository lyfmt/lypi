package cn.lypi.runtime.memory;

/**
 * 表示后台记忆沉淀生命周期中的审计阶段。
 */
public enum MemoryConsolidationAuditStage {
    SKIPPED_THRESHOLD,
    SKIPPED_SESSION_MISMATCH,
    SKIPPED_NO_FORK_POINT,
    ELIGIBLE,
    SUBMITTED,
    SUBMIT_REJECTED,
    RUNNER_FAILED,
    RUN_STARTED,
    FORK_CREATED,
    TURN_COMPLETED,
    RUN_FAILED,
    CLEANED
}
