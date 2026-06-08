package cn.lypi.transport.tui;

import cn.lypi.contracts.agent.TurnRequest;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.InterruptEvent;
import cn.lypi.contracts.runtime.AgentCorePort;
import java.time.Instant;
import java.util.Optional;

final class RuntimeTuiSubmitHandler implements TuiSubmitHandler {
    private final String sessionId;
    private final AgentCorePort core;
    private final EventBus events;
    private MutableAbortSignal activeSignal;

    RuntimeTuiSubmitHandler(String sessionId, AgentCorePort core, EventBus events) {
        this.sessionId = sessionId;
        this.core = core;
        this.events = events;
    }

    @Override
    public void submitUserInput(String input) {
        MutableAbortSignal signal = new MutableAbortSignal();
        activeSignal = signal;
        core.execute(new TurnRequest(
            sessionId,
            input == null ? "" : input,
            Optional.empty(),
            signal
        ));
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
}
