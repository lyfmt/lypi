package cn.lypi.runtime.memory;

import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.EventFilter;
import cn.lypi.contracts.event.EventSubscription;
import cn.lypi.contracts.event.TurnEndEvent;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.session.SessionView;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 监听主 turn 结束事件并异步触发后台记忆沉淀。
 */
public final class MemoryConsolidationTurnEndListener implements AutoCloseable {
    private final EventBus eventBus;
    private final SessionManagerPort sessionManager;
    private final MemoryConsolidationTrigger trigger;
    private final MemoryConsolidationRunner runner;
    private final Executor executor;
    private final MemoryConsolidationAuditSink auditSink;
    private EventSubscription subscription;

    public MemoryConsolidationTurnEndListener(
        EventBus eventBus,
        SessionManagerPort sessionManager,
        MemoryConsolidationTrigger trigger,
        MemoryConsolidationRunner runner,
        Executor executor
    ) {
        this(eventBus, sessionManager, trigger, runner, executor, MemoryConsolidationAuditSink.noop());
    }

    public MemoryConsolidationTurnEndListener(
        EventBus eventBus,
        SessionManagerPort sessionManager,
        MemoryConsolidationTrigger trigger,
        MemoryConsolidationRunner runner,
        Executor executor,
        MemoryConsolidationAuditSink auditSink
    ) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus must not be null");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        this.trigger = Objects.requireNonNull(trigger, "trigger must not be null");
        this.runner = Objects.requireNonNull(runner, "runner must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink must not be null");
    }

    /**
     * 开始订阅 turn end 事件。
     */
    public synchronized void start() {
        if (subscription != null) {
            return;
        }
        subscription = eventBus.subscribe(
            new EventFilter(Optional.empty(), Optional.of(TurnEndEvent.class)),
            envelope -> onTurnEnd((TurnEndEvent) envelope.event())
        );
    }

    private void onTurnEnd(TurnEndEvent event) {
        if (!trigger.shouldTrigger(event, false)) {
            audit(MemoryConsolidationAuditStage.SKIPPED_THRESHOLD, event, null, null, "threshold not met", null);
            return;
        }
        audit(MemoryConsolidationAuditStage.ELIGIBLE, event, null, null, "threshold met", null);
        SessionView view = sessionManager.currentView();
        if (!event.sessionId().equals(view.sessionId())) {
            audit(MemoryConsolidationAuditStage.SKIPPED_SESSION_MISMATCH, event, null, null, "current session mismatch", null);
            return;
        }
        String forkPointEntryId = view.leafId();
        if (forkPointEntryId == null || forkPointEntryId.isBlank()) {
            audit(MemoryConsolidationAuditStage.SKIPPED_NO_FORK_POINT, event, null, null, "missing fork point", null);
            return;
        }
        MemoryConsolidationRequest request = new MemoryConsolidationRequest(event.sessionId(), forkPointEntryId);
        try {
            executor.execute(() -> runQuietly(request));
            audit(MemoryConsolidationAuditStage.SUBMITTED, event, forkPointEntryId, null, "submitted", null);
        } catch (RejectedExecutionException exception) {
            audit(MemoryConsolidationAuditStage.SUBMIT_REJECTED, event, forkPointEntryId, null, "executor rejected", exception);
            // NOTE: 应用关闭或有界队列拒绝时，后台沉淀必须静默放弃。
        }
    }

    private void runQuietly(MemoryConsolidationRequest request) {
        try {
            runner.run(request);
        } catch (RuntimeException exception) {
            audit(MemoryConsolidationAuditStage.RUNNER_FAILED, request, "runner failed", exception);
            // NOTE: 后台沉淀失败不能影响主 turn 结束发布链路。
        }
    }

    private void audit(
        MemoryConsolidationAuditStage stage,
        TurnEndEvent event,
        String forkPointEntryId,
        String forkSessionId,
        String reason,
        Throwable error
    ) {
        if (event == null) {
            return;
        }
        auditSink.record(new MemoryConsolidationAuditRecord(
            stage,
            event.sessionId(),
            event.turnId(),
            forkPointEntryId,
            forkSessionId,
            event.durationMillis(),
            event.toolRounds(),
            reason,
            errorSummary(error),
            event.timestamp()
        ));
    }

    private void audit(MemoryConsolidationAuditStage stage, MemoryConsolidationRequest request, String reason, Throwable error) {
        if (request == null) {
            return;
        }
        auditSink.record(new MemoryConsolidationAuditRecord(
            stage,
            request.sessionId(),
            null,
            request.forkPointEntryId(),
            null,
            0L,
            0,
            reason,
            errorSummary(error),
            null
        ));
    }

    private static String errorSummary(Throwable error) {
        if (error == null) {
            return null;
        }
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    @Override
    public synchronized void close() {
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
    }
}
