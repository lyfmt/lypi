package cn.lypi.agent.compact;

import cn.lypi.contracts.model.TokenUsage;

/**
 * 表示 compact 摘要生成结果。
 *
 * summary 会直接写入 CompactionEntry.summary；usage 仅表示本次摘要调用消耗，
 * 不表示 compact 后上下文大小。
 */
public record CompactSummaryResult(
    String summary,
    TokenUsage usage
) {}
