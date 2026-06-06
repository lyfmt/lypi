package cn.lypi.agent.compact;

/**
 * 表示 AI compact summary 失败后的兼容策略。
 *
 * NOTE: dev 已删除确定性摘要器；FALLBACK_DETERMINISTIC 仅保留旧配置绑定兼容，
 * 当前行为与 SKIP_COMPACTION 一样由 coordinator 回到原上下文。
 */
public enum CompactionSummaryFallbackPolicy {
    FALLBACK_DETERMINISTIC,
    SKIP_COMPACTION
}
