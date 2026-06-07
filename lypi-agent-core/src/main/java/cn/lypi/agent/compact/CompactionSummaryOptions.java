package cn.lypi.agent.compact;

/**
 * 表示 compact summary 运行选项。
 *
 * NOTE: summary 模型和 thinking level 固定沿用当前 ContextSnapshot，
 * 这里不提供模型相关覆盖配置。
 * NOTE: fallbackPolicy 保留配置兼容性；确定性摘要器已删除，AI 失败会交由
 * coordinator 回到原上下文。
 */
public record CompactionSummaryOptions(
    CompactionSummaryFallbackPolicy fallbackPolicy
) {
    public static CompactionSummaryOptions defaults() {
        return new CompactionSummaryOptions(
            CompactionSummaryFallbackPolicy.FALLBACK_DETERMINISTIC
        );
    }

    public CompactionSummaryOptions {
        fallbackPolicy = fallbackPolicy == null
            ? CompactionSummaryFallbackPolicy.FALLBACK_DETERMINISTIC
            : fallbackPolicy;
    }
}
