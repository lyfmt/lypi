package cn.lypi.transport.tui;

import cn.lypi.contracts.agent.TurnRequest;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.InterruptEvent;
import cn.lypi.contracts.event.PermissionResponseEvent;
import cn.lypi.contracts.runtime.AgentCorePort;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executor;

final class RuntimeTuiSubmitHandler implements TuiSubmitHandler {
    private final String sessionId;
    private final AgentCorePort core;
    private final EventBus events;
    private final Executor executor;
    private MutableAbortSignal activeSignal;

    RuntimeTuiSubmitHandler(String sessionId, AgentCorePort core, EventBus events) {
        this(sessionId, core, events, command -> Thread.ofVirtual().name("lypi-tui-turn-", 0).start(command));
    }

    RuntimeTuiSubmitHandler(String sessionId, AgentCorePort core, EventBus events, Executor executor) {
        this.sessionId = sessionId;
        this.core = core;
        this.events = events;
        this.executor = executor;
    }

    @Override
    public void submitUserInput(String input) {
        MutableAbortSignal signal = new MutableAbortSignal();
        activeSignal = signal;
        TurnRequest request = new TurnRequest(
            sessionId,
            input == null ? "" : input,
            Optional.empty(),
            signal
        );
        executor.execute(() -> core.execute(request));
    }

    @Override
    public void requestInterrupt(String reason) {
        if (activeSignal != null) {
            activeSignal.abort();
        }
        events.publish(new InterruptEvent(
            sessionId,
            reason == null || reason.isBlank() ? "interrupt" : reason,
            Instant.now()
        ));
    }

    @Override
    public void submitPermissionOption(String requestId, String toolUseId, String optionId) {
        events.publish(new PermissionResponseEvent(
            sessionId,
            requestId,
            optionId,
            false,
            Instant.now()
        ));
    }
}
