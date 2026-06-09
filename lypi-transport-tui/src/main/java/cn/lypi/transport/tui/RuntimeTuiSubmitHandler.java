package cn.lypi.transport.tui;

import cn.lypi.contracts.agent.TurnRequest;
import cn.lypi.contracts.context.ContentBlockKind;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.InterruptEvent;
import cn.lypi.contracts.event.MessageBlockSnapshot;
import cn.lypi.contracts.event.MessageDeltaEvent;
import cn.lypi.contracts.event.MessageEndEvent;
import cn.lypi.contracts.event.MessageStartEvent;
import cn.lypi.contracts.event.PermissionResponseEvent;
import cn.lypi.contracts.prompt.PromptParameter;
import cn.lypi.contracts.runtime.AgentCorePort;
import cn.lypi.contracts.tui.SlashCommand;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

final class RuntimeTuiSubmitHandler implements TuiSubmitHandler {
    private final String sessionId;
    private final AgentCorePort core;
    private final EventBus events;
    private final Executor executor;
    private final List<SlashCommand> slashCommands;
    private MutableAbortSignal activeSignal;

    RuntimeTuiSubmitHandler(String sessionId, AgentCorePort core, EventBus events) {
        this(sessionId, core, events, command -> Thread.ofVirtual().name("lypi-tui-turn-", 0).start(command));
    }

    RuntimeTuiSubmitHandler(String sessionId, AgentCorePort core, EventBus events, Executor executor) {
        this(sessionId, core, events, executor, List.of());
    }

    RuntimeTuiSubmitHandler(
        String sessionId,
        AgentCorePort core,
        EventBus events,
        Executor executor,
        List<SlashCommand> slashCommands
    ) {
        this.sessionId = sessionId;
        this.core = core;
        this.events = events;
        this.executor = executor;
        this.slashCommands = slashCommands == null ? List.of() : List.copyOf(slashCommands);
    }

    @Override
    public void submitUserInput(String input) {
        if (trySubmitSlashCommand(input)) {
            return;
        }
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

    private boolean trySubmitSlashCommand(String input) {
        String trimmed = input == null ? "" : input.trim();
        if (!trimmed.startsWith("/") || trimmed.length() == 1) {
            return false;
        }
        String[] tokens = trimmed.substring(1).split("\\s+");
        Optional<SlashCommand> command = slashCommands.stream()
            .filter(candidate -> candidate.name().equals(tokens[0]))
            .findFirst();
        if (command.isEmpty()) {
            return false;
        }
        command.get().handler().handle(arguments(command.get(), tokens));
        publishSlashOutput(command.get().name(), output(command.get()));
        return true;
    }

    private Map<String, String> arguments(SlashCommand command, String[] tokens) {
        Map<String, String> arguments = new LinkedHashMap<>();
        if (applyMailboxShorthand(command.name(), tokens, arguments)) {
            return Map.copyOf(arguments);
        }
        List<PromptParameter> parameters = command.parameters();
        for (int index = 1; index < tokens.length; index++) {
            String token = tokens[index];
            int equals = token.indexOf('=');
            if (equals > 0) {
                arguments.put(token.substring(0, equals), token.substring(equals + 1));
                continue;
            }
            int parameterIndex = index - 1;
            if (parameterIndex < parameters.size()) {
                arguments.put(parameters.get(parameterIndex).name(), token);
            }
        }
        return Map.copyOf(arguments);
    }

    private boolean applyMailboxShorthand(String commandName, String[] tokens, Map<String, String> arguments) {
        if (!"mailbox".equals(commandName) || tokens.length < 3 || tokens[1].contains("=") || tokens[2].contains("=")) {
            return false;
        }
        if (isMailboxCommandAction(tokens[1])) {
            arguments.put("action", tokens[1]);
            arguments.put("mailId", tokens[2]);
            return true;
        }
        return false;
    }

    private boolean isMailboxCommandAction(String action) {
        return "accept".equals(action) || "stash".equals(action) || "discard".equals(action);
    }

    private String output(SlashCommand command) {
        return command.handler().lastOutput();
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
