package cn.lypi.transport.tui;

import cn.lypi.contracts.agent.TurnRequest;
import cn.lypi.contracts.context.ContentBlockKind;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.ErrorEvent;
import cn.lypi.contracts.event.InterruptEvent;
import cn.lypi.contracts.event.MessageDeltaEvent;
import cn.lypi.contracts.event.PermissionResponseEvent;
import cn.lypi.contracts.runtime.AgentCorePort;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

final class RuntimeTuiSubmitHandler implements TuiSubmitHandler {
    private final String sessionId;
    private final AgentCorePort core;
    private final EventBus events;
    private final Executor executor;
    private final SlashCommandRouter slashCommandRouter;
    private MutableAbortSignal activeSignal;

    RuntimeTuiSubmitHandler(String sessionId, AgentCorePort core, EventBus events) {
        this(sessionId, core, events, command -> Thread.ofVirtual().name("lypi-tui-turn-", 0).start(command));
    }

    RuntimeTuiSubmitHandler(String sessionId, AgentCorePort core, EventBus events, Executor executor) {
        this(sessionId, core, events, executor, null);
    }

    RuntimeTuiSubmitHandler(
        String sessionId,
        AgentCorePort core,
        EventBus events,
        Executor executor,
        SlashCommandRouter slashCommandRouter
    ) {
        this.sessionId = sessionId;
        this.core = core;
        this.events = events;
        this.executor = executor;
        this.slashCommandRouter = slashCommandRouter;
    }

    @Override
    public void submitUserInput(String input) {
        String routedInput = input == null ? "" : input;
        if (slashCommandRouter != null) {
            SlashCommandResult result = slashCommandRouter.route(routedInput);
            result.message().ifPresent(this::publishSlashCommandError);
            result.notice().ifPresent(this::publishSlashCommandNotice);
            if (result.consumed()) {
                return;
            }
            if (result.prompt().isPresent()) {
                routedInput = result.prompt().orElseThrow();
            }
        }
        MutableAbortSignal signal = new MutableAbortSignal();
        activeSignal = signal;
        TurnRequest request = new TurnRequest(
            sessionId,
            routedInput,
            Optional.empty(),
            signal
        );
        executor.execute(() -> core.execute(request));
    }

    private void publishSlashCommandError(String message) {
        events.publish(new ErrorEvent(
            sessionId,
            "slash_command_error",
            message,
            Instant.now()
        ));
    }

    private void publishSlashCommandNotice(String message) {
        String messageId = "slash_command_" + UUID.randomUUID().toString().replace("-", "");
        events.publish(new MessageDeltaEvent(
            sessionId,
            messageId,
            MessageRole.SYSTEM_LOCAL,
            MessageKind.TEXT,
            messageId + ":text:0",
            ContentBlockKind.TEXT,
            message,
            true,
            Map.of("source", "slash_command"),
            Instant.now()
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
