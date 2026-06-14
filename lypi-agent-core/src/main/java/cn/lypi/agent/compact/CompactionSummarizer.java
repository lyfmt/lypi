package cn.lypi.agent.compact;

public interface CompactionSummarizer {
    /**
     * 生成一次 session 压缩摘要。
     *
     * NOTE: AI 请求必须以 request.context().messages() 作为主体消息前缀，
     * request.plan() 和 request.branchEntries() 仅用于边界记录、审计和调试。
     */
    CompactSummaryResult summarize(CompactSummaryRequest request);
}
