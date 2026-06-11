package cn.lypi.boot.runtime;

import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.tui.ResumeSessionController;
import cn.lypi.contracts.tui.SessionBranchTreeView;
import cn.lypi.contracts.tui.SessionResumeInfo;
import cn.lypi.session.SessionBranchTreeQuery;
import cn.lypi.session.SessionResumeQuery;
import java.nio.file.Path;
import java.util.List;

final class DefaultResumeSessionController implements ResumeSessionController {
    private final Path cwd;
    private final SessionManagerPort sessionManager;

    DefaultResumeSessionController(Path cwd, SessionManagerPort sessionManager) {
        this.cwd = cwd;
        this.sessionManager = sessionManager;
    }

    @Override
    public List<SessionResumeInfo> sessions() {
        return new SessionResumeQuery(cwd).sessions();
    }

    @Override
    public SessionBranchTreeView tree(String sessionId) {
        return new SessionBranchTreeQuery(cwd).tree(sessionId);
    }

    @Override
    public void resume(String sessionId, String leafId) {
        sessionManager.openOrCreate(sessionId);
        sessionManager.switchLeaf(leafId);
    }
}
