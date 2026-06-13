package cn.lypi.contracts.session;

import java.util.List;
import java.util.Optional;

/**
 * 表示一次分支切换前可供总结的旧路径片段。
 *
 * NOTE: entries 按时间顺序排列，只包含 oldLeafId 到共同祖先之间的路径后缀。
 */
public record BranchSummaryPlan(
    String oldLeafId,
    String targetLeafId,
    Optional<String> commonAncestorId,
    List<SessionEntry> entries
) {
    public BranchSummaryPlan {
        commonAncestorId = commonAncestorId == null ? Optional.empty() : commonAncestorId;
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /**
     * 返回该计划是否包含可进入 branch summary 请求的内容。
     */
    public boolean hasSummarizableContent() {
        return entries.stream().anyMatch(this::isSummarizable);
    }

    private boolean isSummarizable(SessionEntry entry) {
        return entry instanceof MessageEntry
            || entry instanceof CustomMessageEntry
            || entry instanceof BranchSummaryEntry
            || entry instanceof CompactionEntry;
    }
}
