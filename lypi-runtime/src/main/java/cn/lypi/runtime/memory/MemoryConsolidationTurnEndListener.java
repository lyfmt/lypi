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

/**
 * 监听主 turn 结束事件并异步触发后台记忆沉淀。
 */
public final class MemoryConsolidationTurnEndListener implements AutoCloseable {
    private final EventBus eventBus;
    private final SessionManagerPort sessionManager;
    private final MemoryConsolidationTrigger trigger;
    private final MemoryConsolidationRunner runner;
    private final Executor executor;
    private EventSubscription subscription;

    public MemoryConsolidationTurnEndListener(
        EventBus eventBus,
        SessionManagerPort sessionManager,
        MemoryConsolidationTrigger trigger,
        MemoryConsolidationRunner runner,
        Executor executor
    ) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus must not be null");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        this.trigger = Objects.requireNonNull(trigger, "trigger must not be null");
        this.runner = Objects.requireNonNull(runner, "runner must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
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
            return;
        }
        SessionView view = sessionManager.currentView();
        String forkPointEntryId = view.leafId();
        if (forkPointEntryId == null || forkPointEntryId.isBlank()) {
            return;
        }
        MemoryConsolidationRequest request = new MemoryConsolidationRequest(event.sessionId(), forkPointEntryId);
        executor.execute(() -> runQuietly(request));
    }

    private void runQuietly(MemoryConsolidationRequest request) {
        try {
            runner.run(request);
        } catch (RuntimeException exception) {
            // NOTE: 后台沉淀失败不能影响主 turn 结束发布链路。
        }
    }

    @Override
    public synchronized void close() {
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
    }
}
