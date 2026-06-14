package cn.lypi.contracts.tui;

import java.util.List;
import java.util.Optional;

public interface ResumeSessionController {
    /**
     * 返回可恢复的 session 列表。
     */
    List<SessionResumeInfo> sessions();

    /**
     * 返回指定 session 的 branch tree。
     */
    SessionBranchTreeView tree(String sessionId);

    /**
     * 恢复到指定 session leaf。
     */
    SessionRuntimeState resume(String sessionId, String leafId);

    /**
     * 判断恢复到目标 leaf 前是否可以总结当前离开的分支。
     */
    default Optional<BranchSummaryOffer> branchSummaryOffer(String sessionId, String targetLeafId) {
        return Optional.empty();
    }

    /**
     * 生成 branch summary，插入目标 leaf 后并恢复到 summary leaf。
     */
    default SessionRuntimeState resumeWithBranchSummary(String sessionId, String targetLeafId) {
        return resume(sessionId, targetLeafId);
    }
}
