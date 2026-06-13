package cn.lypi.boot.runtime;

import cn.lypi.agent.branch.AiBranchSummarizer;
import cn.lypi.agent.branch.BranchSummaryRequest;
import cn.lypi.agent.branch.BranchSummaryResult;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.SessionStateEvent;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.session.BranchSummaryPlan;
import cn.lypi.contracts.session.SessionContext;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.tui.BranchSummaryOffer;
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
import java.util.Optional;

final class DefaultResumeSessionController implements ResumeSessionController {
    private final Path cwd;
    private final SessionManagerPort sessionManager;
    private final EventBus events;
    private final AiBranchSummarizer branchSummarizer;

    DefaultResumeSessionController(Path cwd, SessionManagerPort sessionManager, EventBus events) {
        this(cwd, sessionManager, events, null);
    }

    DefaultResumeSessionController(
        Path cwd,
        SessionManagerPort sessionManager,
        EventBus events,
        AiBranchSummarizer branchSummarizer
    ) {
        this.cwd = cwd;
        this.sessionManager = sessionManager;
        this.events = events;
        this.branchSummarizer = branchSummarizer;
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
        return runtimeState(handle);
    }

    @Override
    public Optional<BranchSummaryOffer> branchSummaryOffer(String sessionId, String targetLeafId) {
        if (branchSummarizer == null || sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        String currentSessionId = sessionManager.currentView().sessionId();
        String oldLeafId = sessionManager.currentView().leafId();
        if (!sessionId.equals(currentSessionId) || targetLeafId == null || targetLeafId.equals(oldLeafId)) {
            return Optional.empty();
        }
        BranchSummaryPlan plan = sessionManager.collectBranchSummaryPlan(oldLeafId, targetLeafId);
        if (!plan.hasSummarizableContent()) {
            return Optional.empty();
        }
        return Optional.of(new BranchSummaryOffer(
            sessionId,
            oldLeafId,
            targetLeafId,
            plan.commonAncestorId().orElse(null),
            plan.summarizableEntryCount()
        ));
    }

    @Override
    public SessionRuntimeState resumeWithBranchSummary(String sessionId, String targetLeafId) {
        if (branchSummarizer == null || !sessionId.equals(sessionManager.currentView().sessionId())) {
            return resume(sessionId, targetLeafId);
        }
        String oldLeafId = sessionManager.currentView().leafId();
        BranchSummaryPlan plan = sessionManager.collectBranchSummaryPlan(oldLeafId, targetLeafId);
        if (!plan.hasSummarizableContent()) {
            return resume(sessionId, targetLeafId);
        }
        SessionContext currentContext = sessionManager.context(oldLeafId);
        BranchSummaryResult result = branchSummarizer.summarize(new BranchSummaryRequest(
            new ContextSnapshot(
                null,
                currentContext.messages(),
                currentContext.model(),
                currentContext.thinkingLevel(),
                currentContext.mode(),
                currentContext.permissionMode(),
                new ContextBudget(0, 128_000, 100_000, 8_192, 16_384, 0L, 0L, BigDecimal.ZERO)
            ),
            plan,
            () -> false
        ));
        return runtimeState(sessionManager.appendBranchSummary(targetLeafId, oldLeafId, result.summary()));
    }

    private SessionRuntimeState runtimeState(SessionHandle handle) {
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
