package cn.lypi.transport.tui;

import cn.lypi.contracts.agent.TurnRequest;
import cn.lypi.contracts.context.ContentBlockKind;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.ErrorEvent;
import cn.lypi.contracts.event.InterruptEvent;
import cn.lypi.contracts.event.MessageBlockSnapshot;
import cn.lypi.contracts.event.MessageDeltaEvent;
import cn.lypi.contracts.event.MessageEndEvent;
import cn.lypi.contracts.event.MessageStartEvent;
import cn.lypi.contracts.event.PermissionResponseEvent;
import cn.lypi.contracts.event.SessionStateEvent;
import cn.lypi.contracts.session.SessionContext;
import cn.lypi.contracts.runtime.AgentCorePort;
import cn.lypi.contracts.tui.SlashCommand;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        this(sessionId, core, events, executor, (SlashCommandRouter) null);
    }

    RuntimeTuiSubmitHandler(
        String sessionId,
        AgentCorePort core,
        EventBus events,
        Executor executor,
        List<SlashCommand> slashCommands
    ) {
        this(
            sessionId,
            core,
            events,
            executor,
            slashCommands == null || slashCommands.isEmpty() ? null : new SlashCommandRouter(slashCommands)
        );
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
            if (result.consumed()) {
                if (result.stateChanged() && slashCommandRouter != null) {
                    publishSessionState();
                }
                result.notice().ifPresent(this::publishSlashCommandNotice);
                return;
            }
            result.notice().ifPresent(this::publishSlashCommandNotice);
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
        publishSlashOutput("slash_command", message);
    }

    private void publishSessionState() {
        slashCommandRouter.sessionContext().ifPresent(context -> events.publish(new SessionStateEvent(
            sessionId,
            slashCommandRouter.currentLeafIdForState().orElse(""),
            context.model(),
            context.thinkingLevel(),
            context.mode(),
            context.permissionMode(),
            Instant.now()
        )));
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

    private void publishSlashOutput(String commandName, String output) {
        if (output == null || output.isBlank()) {
            return;
        }
        Instant now = Instant.now();
        String messageId = "msg_slash_" + commandName + "_" + Long.toUnsignedString(now.toEpochMilli());
        String blockId = messageId + ":text:0";
        Map<String, Object> metadata = Map.of("slashCommand", commandName);
        events.publish(new MessageStartEvent(
            sessionId,
            messageId,
            MessageRole.SYSTEM_LOCAL,
            MessageKind.TEXT,
            metadata,
            now
        ));
        events.publish(new MessageDeltaEvent(
            sessionId,
            messageId,
            MessageRole.SYSTEM_LOCAL,
            MessageKind.TEXT,
            blockId,
            ContentBlockKind.TEXT,
            output,
            true,
            metadata,
            now
        ));
        events.publish(new MessageEndEvent(
            sessionId,
            messageId,
            MessageRole.SYSTEM_LOCAL,
            MessageKind.TEXT,
            List.of(new MessageBlockSnapshot(blockId, ContentBlockKind.TEXT, output, metadata)),
            Optional.empty(),
            Optional.empty(),
            metadata,
            now
        ));
    }
}
