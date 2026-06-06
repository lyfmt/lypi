package cn.lypi.agent.compact;

import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.session.CompactionPlan;
import cn.lypi.contracts.session.SessionEntry;
import java.util.List;

public interface CompactionSummarizer {
    /**
     * 生成一次 session 压缩摘要。
     *
     * 摘要只应覆盖 plan 中列出的历史条目，并保留后续恢复上下文所需的关键事实。
     */
    String summarize(List<SessionEntry> branchEntries, CompactionPlan plan, ContextSnapshot context);
}
