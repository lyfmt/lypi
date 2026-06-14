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
import cn.lypi.contracts.runtime.CompactionResult;
import cn.lypi.contracts.skill.SkillIndex;
import cn.lypi.contracts.skill.SkillMention;
import cn.lypi.contracts.tui.SessionRuntimeState;
import cn.lypi.contracts.tui.SlashCommand;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class RuntimeTuiSubmitHandler implements TuiSubmitHandler {
    private String currentSessionId;
    private final AgentCorePort core;
    private final EventBus events;
    private final Executor executor;
    private final SlashCommandRouter slashCommandRouter;
    private final Consumer<SessionRuntimeState> runtimeStateConsumer;
    private final Supplier<SkillIndex> skillIndexSupplier;
    private MutableAbortSignal activeSignal;
    private volatile boolean compactRunning;

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
        this(sessionId, core, events, executor, slashCommands, null);
    }

    RuntimeTuiSubmitHandler(
        String sessionId,
        AgentCorePort core,
        EventBus events,
        Executor executor,
        List<SlashCommand> slashCommands,
        Consumer<SessionRuntimeState> runtimeStateConsumer
    ) {
        this(
            sessionId,
            core,
            events,
            executor,
            slashCommands == null || slashCommands.isEmpty() ? null : new SlashCommandRouter(slashCommands),
            runtimeStateConsumer
        );
    }

    RuntimeTuiSubmitHandler(
        String sessionId,
        AgentCorePort core,
        EventBus events,
        Executor executor,
        SlashCommandRouter slashCommandRouter
    ) {
        this(sessionId, core, events, executor, slashCommandRouter, null);
    }

    RuntimeTuiSubmitHandler(
        String sessionId,
        AgentCorePort core,
        EventBus events,
        Executor executor,
        SlashCommandRouter slashCommandRouter,
        Consumer<SessionRuntimeState> runtimeStateConsumer
    ) {
        this(sessionId, core, events, executor, slashCommandRouter, runtimeStateConsumer, () -> new SkillIndex(List.of(), List.of()));
    }

    RuntimeTuiSubmitHandler(
        String sessionId,
        AgentCorePort core,
        EventBus events,
        Executor executor,
        SlashCommandRouter slashCommandRouter,
        Consumer<SessionRuntimeState> runtimeStateConsumer,
        Supplier<SkillIndex> skillIndexSupplier
    ) {
        this.currentSessionId = sessionId;
        this.core = core;
        this.events = events;
        this.executor = executor;
        this.slashCommandRouter = slashCommandRouter;
        this.runtimeStateConsumer = runtimeStateConsumer;
        this.skillIndexSupplier = skillIndexSupplier == null
            ? () -> new SkillIndex(List.of(), List.of())
            : skillIndexSupplier;
    }

    @Override
    public void submitUserInput(String input) {
        submitUserInput(input, List.of());
    }

    @Override
    public void submitUserInput(String input, List<SkillMention> skillMentions) {
        String routedInput = input == null ? "" : input;
        if (slashCommandRouter != null) {
            SlashCommandResult compactValidation = slashCommandRouter.compactValidation(routedInput);
            if (compactValidation.matched()) {
                compactValidation.message().ifPresent(this::publishSlashCommandError);
                if (compactValidation.message().isEmpty()) {
                    submitCompact(routedInput);
                }
                return;
            }
        }
        if (compactRunning) {
            publishSlashCommandError("compact: compaction is running");
            return;
        }
        if (slashCommandRouter != null) {
            SlashCommandResult result = slashCommandRouter.route(routedInput);
            result.message().ifPresent(this::publishSlashCommandError);
            if (result.consumed()) {
                result.runtimeState().ifPresent(this::switchRuntimeState);
                if (result.stateChanged() && result.runtimeState().isEmpty() && slashCommandRouter != null) {
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
        List<SkillMention> resolvedSkillMentions = skillMentions == null || skillMentions.isEmpty()
            ? new SkillMentionParser(skillIndexSupplier.get().skills()).explicitMentions(routedInput, List.of(), null)
            : List.copyOf(skillMentions);
        MutableAbortSignal signal = new MutableAbortSignal();
        activeSignal = signal;
        String sessionId = currentSessionId;
        TurnRequest request = new TurnRequest(
            sessionId,
            routedInput,
            Optional.empty(),
            signal,
            TurnRequest.DEFAULT_MAX_TOOL_ROUNDS,
            resolvedSkillMentions
        );
        executor.execute(() -> core.execute(request));
    }

    private void submitCompact(String input) {
        if (compactRunning) {
            publishSlashCommandError("compact: compaction is running");
            return;
        }
        MutableAbortSignal signal = new MutableAbortSignal();
        Optional<CompactCommandInvocation> invocation = slashCommandRouter.compactInvocation(input, signal);
        if (invocation.isEmpty()) {
            publishSlashCommandError("compact: compaction runtime is unavailable");
            return;
        }
        activeSignal = signal;
        compactRunning = true;
        executor.execute(() -> runCompact(invocation.orElseThrow(), signal));
    }

    private void runCompact(CompactCommandInvocation invocation, MutableAbortSignal signal) {
        try {
            CompactionResult result = invocation.runtime().compact(invocation.request());
            if (result.compacted()) {
                publishSlashCommandNotice("compact: " + result.message());
            } else {
                publishSlashCommandError("compact: " + result.message());
            }
        } catch (RuntimeException exception) {
            publishSlashCommandError("compact: " + errorMessage(exception));
        } finally {
            compactRunning = false;
            if (activeSignal == signal) {
                activeSignal = null;
            }
        }
    }

    private void publishSlashCommandError(String message) {
        events.publish(new ErrorEvent(
            currentSessionId,
            "slash_command_error",
            message,
            Instant.now()
        ));
    }

    private void publishSlashCommandNotice(String message) {
        publishSlashOutput("slash_command", message);
    }

    private String errorMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private void publishSessionState() {
        slashCommandRouter.sessionContext().ifPresent(context -> events.publish(new SessionStateEvent(
            currentSessionId,
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
            currentSessionId,
            reason == null || reason.isBlank() ? "interrupt" : reason,
            Instant.now()
        ));
    }

    @Override
    public void submitPermissionOption(String requestId, String toolUseId, String optionId) {
        events.publish(new PermissionResponseEvent(
            currentSessionId,
            requestId,
            optionId,
            false,
            Instant.now()
        ));
    }

    @Override
    public void resumeSession(String sessionId, String leafId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        currentSessionId = sessionId;
    }

    private void switchRuntimeState(SessionRuntimeState state) {
        if (state == null || state.sessionId() == null || state.sessionId().isBlank()) {
            return;
        }
        currentSessionId = state.sessionId();
        if (runtimeStateConsumer != null) {
            runtimeStateConsumer.accept(state);
        }
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
            currentSessionId,
            messageId,
            MessageRole.SYSTEM_LOCAL,
            MessageKind.TEXT,
            metadata,
            now
        ));
        events.publish(new MessageDeltaEvent(
            currentSessionId,
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
            currentSessionId,
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
