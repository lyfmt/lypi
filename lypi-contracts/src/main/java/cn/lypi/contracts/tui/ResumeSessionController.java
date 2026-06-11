package cn.lypi.contracts.tui;

import java.util.List;

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
}
