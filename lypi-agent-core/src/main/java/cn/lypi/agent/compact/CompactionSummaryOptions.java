package cn.lypi.agent.compact;

import cn.lypi.contracts.model.ThinkingLevel;

/**
 * 表示 compact summary 运行选项。
 *
 * NOTE: summary 模型固定沿用当前 ContextSnapshot.model()，这里不提供模型覆盖配置。
 */
public record CompactionSummaryOptions(
    ThinkingLevel thinkingLevel,
    CompactionSummaryFallbackPolicy fallbackPolicy
) {
    public static CompactionSummaryOptions defaults() {
        return new CompactionSummaryOptions(
            ThinkingLevel.OFF,
            CompactionSummaryFallbackPolicy.FALLBACK_DETERMINISTIC
        );
    }

    public CompactionSummaryOptions {
        thinkingLevel = thinkingLevel == null ? ThinkingLevel.OFF : thinkingLevel;
        fallbackPolicy = fallbackPolicy == null
            ? CompactionSummaryFallbackPolicy.FALLBACK_DETERMINISTIC
            : fallbackPolicy;
    }
}
