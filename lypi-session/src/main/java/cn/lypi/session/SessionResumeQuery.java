package cn.lypi.session;

import cn.lypi.contracts.tui.SessionResumeInfo;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * 查询当前 cwd 下可恢复的 session 列表。
 */
public final class SessionResumeQuery {
    private final JsonlSessionStore store;

    public SessionResumeQuery(Path cwd) {
        this.store = new JsonlSessionStore(cwd);
    }

    /**
     * 返回 Pi session selector 风格的 session 信息。
     */
    public List<SessionResumeInfo> sessions() {
        return store.resumeScans().stream()
            .map(this::info)
            .sorted(Comparator.comparing(SessionResumeInfo::modified).reversed())
            .toList();
    }

    private SessionResumeInfo info(SessionResumeScan scan) {
        return new SessionResumeInfo(
            scan.path(),
            scan.header().id(),
            scan.header().cwd(),
            scan.header().parentSessionId().map(store::sessionFile),
            scan.leafId(),
            scan.header().timestamp(),
            scan.modified(),
            scan.messageCount(),
            scan.firstMessage(),
            scan.allMessagesText()
        );
    }
}
