package cn.lypi.runtime.memory;

import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.EventFilter;
import cn.lypi.contracts.event.EventSubscription;
import cn.lypi.contracts.event.TurnEndEvent;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.session.SessionView;
import cn.lypi.contracts.context.AgentMessage;
import java.util.List;
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
    private final MemoryWriteDetector memoryWriteDetector;
    private final MemoryConsolidationTrigger.ExtractionState extractionState = new MemoryConsolidationTrigger.ExtractionState();
    private EventSubscription subscription;
    private boolean running;
    private boolean closing;
    private MemoryConsolidationRequest pending;

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
        this.memoryWriteDetector = new MemoryWriteDetector();
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
        if (isClosing()) {
            return;
        }
        if (!trigger.isEligible(event, false)) {
            audit(MemoryConsolidationAuditStage.SKIPPED_THRESHOLD, event, null, null, "threshold not met", null);
            return;
        }
        audit(MemoryConsolidationAuditStage.ELIGIBLE, event, null, null, "completed turn eligible for background gate", null);
        SessionView view = sessionManager.currentView();
        if (!event.sessionId().equals(view.sessionId())) {
            audit(MemoryConsolidationAuditStage.SKIPPED_SESSION_MISMATCH, event, null, null, "current session mismatch", null);
            return;
        }
        String forkPointEntryId = event.leafEntryId();
        if (forkPointEntryId == null || forkPointEntryId.isBlank()) {
            audit(MemoryConsolidationAuditStage.SKIPPED_NO_FORK_POINT, event, null, null, "missing fork point", null);
            return;
        }
        MemoryConsolidationRequest request = new MemoryConsolidationRequest(event.sessionId(), forkPointEntryId);
        if (coalesceOrMarkRunning(request, event)) {
            return;
        }
        try {
            executor.execute(() -> drainQuietly(request));
            audit(MemoryConsolidationAuditStage.SUBMITTED, event, forkPointEntryId, null, "submitted", null);
        } catch (RejectedExecutionException exception) {
            clearRunning(request);
            audit(MemoryConsolidationAuditStage.SUBMIT_REJECTED, event, forkPointEntryId, null, "executor rejected", exception);
            // NOTE: 应用关闭或有界队列拒绝时，后台沉淀必须静默放弃。
        }
    }

    private List<AgentMessage> transcript(MemoryConsolidationRequest request) {
        return sessionManager.transcript(request.forkPointEntryId());
    }

    private boolean mainTurnAlreadyWroteMemory(List<AgentMessage> transcript, MemoryConsolidationRequest request) {
        try {
            return memoryWriteDetector.hasMemoryWrite(transcript);
        } catch (RuntimeException exception) {
            audit(
                MemoryConsolidationAuditStage.DIRECT_WRITE_DETECTION_FAILED,
                request,
                "direct memory write detection failed",
                exception
            );
            return false;
        }
    }

    private synchronized boolean coalesceOrMarkRunning(MemoryConsolidationRequest request, TurnEndEvent event) {
        if (closing) {
            audit(MemoryConsolidationAuditStage.SUBMIT_REJECTED, event, request.forkPointEntryId(), null, "listener closing", null);
            return true;
        }
        if (running) {
            pending = request;
            auditSink.record(new MemoryConsolidationAuditRecord(
                MemoryConsolidationAuditStage.COALESCED,
                event.sessionId(),
                event.turnId(),
                request.forkPointEntryId(),
                null,
                event.durationMillis(),
                event.toolRounds(),
                "coalesced",
                null,
                event.timestamp(),
                java.util.List.of(),
                java.util.List.of(),
                true
            ));
            return true;
        }
        running = true;
        return false;
    }

    private void drainQuietly(MemoryConsolidationRequest firstRequest) {
        MemoryConsolidationRequest current = firstRequest;
        while (current != null) {
            runQuietly(current);
            current = takePendingOrStop();
        }
    }

    private void runQuietly(MemoryConsolidationRequest request) {
        try {
            List<AgentMessage> messages;
            try {
                messages = transcript(request);
            } catch (RuntimeException exception) {
                audit(
                    MemoryConsolidationAuditStage.DIRECT_WRITE_DETECTION_FAILED,
                    request,
                    "background memory gate transcript read failed",
                    exception
                );
                runner.run(request);
                return;
            }
            if (!trigger.shouldExtract(messages, extractionState)) {
                audit(MemoryConsolidationAuditStage.SKIPPED_THRESHOLD, request, "token/tool threshold not met", null);
                return;
            }
            if (mainTurnAlreadyWroteMemory(messages, request)) {
                audit(MemoryConsolidationAuditStage.SKIPPED_DIRECT_WRITE, request, "main turn wrote memory", null);
                return;
            }
            runner.run(request);
        } catch (RuntimeException exception) {
            audit(MemoryConsolidationAuditStage.RUNNER_FAILED, request, "runner failed", exception);
            // NOTE: 后台沉淀失败不能影响主 turn 结束发布链路。
        }
    }

    private synchronized MemoryConsolidationRequest takePendingOrStop() {
        MemoryConsolidationRequest next = pending;
        pending = null;
        if (next == null) {
            running = false;
            notifyAll();
        }
        return next;
    }

    private synchronized void clearRunning(MemoryConsolidationRequest request) {
        if (pending == request) {
            pending = null;
        }
        running = false;
        notifyAll();
    }

    private synchronized boolean isClosing() {
        return closing;
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
    public void close() {
        EventSubscription toClose;
        synchronized (this) {
            closing = true;
            pending = null;
            toClose = subscription;
            subscription = null;
        }
        if (toClose != null) {
            toClose.close();
        }
        synchronized (this) {
            while (running) {
                try {
                    wait();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
