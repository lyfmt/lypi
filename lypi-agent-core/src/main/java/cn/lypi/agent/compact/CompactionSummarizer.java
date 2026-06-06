package cn.lypi.agent.compact;

public interface CompactionSummarizer {
    /**
     * 生成一次 session 压缩摘要。
     *
     * 摘要应保留后续恢复上下文所需的关键事实。
     */
    CompactSummaryResult summarize(CompactSummaryRequest request);
}
