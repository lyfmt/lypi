package cn.lypi.boot.runtime;

import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.SessionStateEvent;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.session.SessionContext;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.tui.ResumeSessionController;
import cn.lypi.contracts.tui.SessionBranchTreeView;
import cn.lypi.contracts.tui.SessionResumeInfo;
import cn.lypi.contracts.tui.SessionRuntimeState;
import java.math.BigDecimal;
import cn.lypi.session.SessionBranchTreeQuery;
import cn.lypi.session.SessionResumeQuery;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

final class DefaultResumeSessionController implements ResumeSessionController {
    private final Path cwd;
    private final SessionManagerPort sessionManager;
    private final EventBus events;

    DefaultResumeSessionController(Path cwd, SessionManagerPort sessionManager, EventBus events) {
        this.cwd = cwd;
        this.sessionManager = sessionManager;
        this.events = events;
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
    public SessionRuntimeState resume(String sessionId, String leafId) {
        sessionManager.openOrCreate(sessionId);
        SessionHandle handle = sessionManager.switchLeaf(leafId);
        SessionContext context = sessionManager.context(handle.leafId());
        SessionStateEvent event = new SessionStateEvent(
            handle.sessionId(),
            handle.leafId(),
            context.model(),
            context.thinkingLevel(),
            context.mode(),
            context.permissionMode(),
            Instant.now()
        );
        events.publish(event);
        return new SessionRuntimeState(
            handle.sessionId(),
            cwd,
            handle.leafId(),
            context.model(),
            context.thinkingLevel(),
            context.mode(),
            context.permissionMode(),
            new ContextBudget(0, 128_000, 100_000, 8_192, 16_384, 0L, 0L, BigDecimal.ZERO),
            sessionManager.transcript(handle.leafId()),
            false,
            false,
            false,
            false
        );
    }
}
