package cn.lypi.contracts.tui;

/**
 * 表示 TUI 可提示用户为一次 resume 分支切换生成 summary。
 */
public record BranchSummaryOffer(
    String sessionId,
    String oldLeafId,
    String targetLeafId,
    String commonAncestorId,
    int entriesToSummarize
) {}
