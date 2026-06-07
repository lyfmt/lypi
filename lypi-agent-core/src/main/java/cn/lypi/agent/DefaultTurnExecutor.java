package cn.lypi.agent;

import cn.lypi.agent.compact.CompactionDecision;
import cn.lypi.agent.compact.CompactionRequest;
import cn.lypi.contracts.agent.PermissionResumeRequest;
import cn.lypi.contracts.agent.TurnRequest;
import cn.lypi.contracts.agent.TurnState;
import cn.lypi.contracts.agent.TurnStatus;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlockKind;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.event.MessageDeltaEvent;
import cn.lypi.contracts.event.MessageEndEvent;
import cn.lypi.contracts.event.MessageStartEvent;
import cn.lypi.contracts.event.PermissionDecisionEvent;
import cn.lypi.contracts.event.PermissionRequestEvent;
import cn.lypi.contracts.event.TurnEndEvent;
import cn.lypi.contracts.event.TurnStartEvent;
import cn.lypi.contracts.event.ToolEndEvent;
import cn.lypi.contracts.event.ToolStartEvent;
import cn.lypi.contracts.model.AssistantEventStream;
import cn.lypi.contracts.model.AssistantStart;
import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.model.TextDelta;
import cn.lypi.contracts.runtime.PendingToolPermissionException;
import cn.lypi.contracts.security.PendingPermission;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionResponse;
import cn.lypi.contracts.session.PermissionDecisionEntry;
import cn.lypi.contracts.session.PermissionPendingEntry;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

public final class DefaultTurnExecutor implements TurnExecutor {
    private final AgentCoreRuntimePorts ports;
    private final TurnIds ids;
    private final Clock clock;
    private final AgentMessageFactory messageFactory;
    private final ToolCallMapper toolCallMapper;
    private final AgentCoreExceptionHandler exceptionHandler;

    public DefaultTurnExecutor(AgentCoreRuntimePorts ports, TurnIds ids, Clock clock) {
        this.ports = ports;
        this.ids = ids;
        this.clock = clock;
        this.messageFactory = new AgentMessageFactory(clock);
        this.toolCallMapper = new ToolCallMapper();
        this.exceptionHandler = new AgentCoreExceptionHandler(ports.eventBus(), messageFactory, clock);
    }

    @Override
    public TurnState execute(TurnRequest request) {
        String turnId = ids.newTurnId();
        List<AgentMessage> newMessages = new ArrayList<>();
        ports.sessionManager().openOrCreate(request.sessionId());
        request.parentEntryId().ifPresent(parentEntryId -> ports.sessionManager().switchLeaf(parentEntryId));
        ports.eventBus().publish(new TurnStartEvent(request.sessionId(), turnId, clock.instant()));

        AgentMessage user = messageFactory.userMessage(ids.newMessageId(), request.userInput());
        String contextLeafId = appendNewMessage(request.sessionId(), user);
        newMessages.add(user);

        ContextSnapshot context = null;
        try {
            context = buildContext(request, Optional.of(contextLeafId));
            AgentMessage assistant = runModel(request, context);
            contextLeafId = appendStartedMessage(request.sessionId(), assistant);
            newMessages.add(assistant);
            if (isAssistantError(assistant, request)) {
                return failedState(turnId, request.sessionId(), context, newMessages, 0);
            }
            return continueFromAssistant(
                turnId,
                request.sessionId(),
                context,
                Optional.of(contextLeafId),
                assistant,
                newMessages,
                0,
                request.maxToolRounds(),
                request.abortSignal()::aborted
            );
        } catch (RuntimeException failure) {
            AgentCoreExceptionHandler.Failure handled = exceptionHandler.handle(
                request.sessionId(),
                ids.newMessageId(),
                failure
            );
            appendNewMessage(request.sessionId(), handled.message());
            newMessages.add(handled.message());
            return failedState(turnId, request.sessionId(), context, newMessages, 0);
        }
    }

    @Override
    public TurnState resumePermission(PermissionResumeRequest request) {
        PermissionResponse response = request.response();
        ports.sessionManager().openOrCreate(response.sessionId());
        PermissionPendingEntry pendingEntry = findPendingPermission(response)
            .orElseThrow(() -> new IllegalStateException("未找到等待中的权限请求: " + response.toolUseId()));
        PendingPermission pendingPermission = pendingEntry.pendingPermission();
        ports.sessionManager().switchLeaf(pendingEntry.id());
        String contextLeafId = appendPermissionDecision(response.sessionId(), response);
        ports.eventBus().publish(new PermissionDecisionEvent(
            response.sessionId(),
            response.toolUseId(),
            pendingPermission.toolName(),
            pendingPermission.renderedToolUse(),
            decisionFromResponse(response, pendingPermission.decision()),
            clock.instant()
        ));

        if (response.behavior() == PermissionBehavior.ABORT) {
            ports.eventBus().publish(new ToolEndEvent(response.sessionId(), response.toolUseId(), true, clock.instant()));
            TurnState state = new TurnState(
                response.turnId(),
                response.sessionId(),
                buildContext(response.sessionId(), Optional.of(contextLeafId), () -> false),
                List.of(),
                pendingEntry.currentToolRound(),
                TurnStatus.ABORTED
            );
            ports.eventBus().publish(new TurnEndEvent(response.sessionId(), response.turnId(), TurnStatus.ABORTED.name(), clock.instant()));
            return state;
        }

        List<AgentMessage> newMessages = new ArrayList<>();
        ContextSnapshot context = buildContext(response.sessionId(), Optional.of(contextLeafId), () -> false);
        try {
            ToolResult<?> toolResult = response.behavior() == PermissionBehavior.ALLOW
                ? resumeTool(response.sessionId(), pendingPermission, context, response)
                : deniedToolResult(response, pendingPermission);
            if (response.behavior() != PermissionBehavior.ALLOW) {
                ports.eventBus().publish(new ToolEndEvent(response.sessionId(), response.toolUseId(), true, clock.instant()));
            }
            String leafId = appendToolResults(response.sessionId(), List.of(toolResult), newMessages);
            context = buildContext(response.sessionId(), Optional.of(leafId), () -> false);
            AgentMessage assistant = runModel(response.sessionId(), context, () -> false);
            leafId = appendStartedMessage(response.sessionId(), assistant);
            newMessages.add(assistant);
            if (isAssistantError(assistant, () -> false)) {
                return failedState(response.turnId(), response.sessionId(), context, newMessages, 0);
            }
            return continueFromAssistant(
                response.turnId(),
                response.sessionId(),
                context,
                Optional.of(leafId),
                assistant,
                newMessages,
                pendingEntry.currentToolRound(),
                pendingEntry.maxToolRounds(),
                () -> false
            );
        } catch (PendingToolPermissionException pending) {
            return waitingPermissionState(
                response.turnId(),
                response.sessionId(),
                context,
                Optional.of(contextLeafId),
                newMessages,
                pendingEntry.currentToolRound() + 1,
                pendingEntry.maxToolRounds(),
                pending.pendingPermission()
            );
        } catch (RuntimeException failure) {
            AgentCoreExceptionHandler.Failure handled = exceptionHandler.handle(
                response.sessionId(),
                ids.newMessageId(),
                failure
            );
            appendNewMessage(response.sessionId(), handled.message());
            newMessages.add(handled.message());
            return failedState(response.turnId(), response.sessionId(), context, newMessages, 0);
        }
    }

    private void extractMemorySafely(TurnState state) {
        try {
            ports.memoryExtractionWorker().extractAfterTurn(state);
        } catch (RuntimeException ignored) {
            // NOTE: 记忆提取是 turn 后置任务，失败不得改变 turn 结果。
        }
    }

    private boolean isAssistantError(AgentMessage assistant, TurnRequest request) {
        return isAssistantError(assistant, request.abortSignal()::aborted);
    }

    private boolean isAssistantError(AgentMessage assistant, BooleanSupplier abortSignal) {
        return !abortSignal.getAsBoolean()
            && assistant.content().stream().anyMatch(block -> block.kind() == ContentBlockKind.ERROR);
    }

    private boolean hasIncompleteToolCalls(AgentMessage assistant) {
        return assistant.content().stream()
            .filter(ToolCallContentBlock.class::isInstance)
            .map(ToolCallContentBlock.class::cast)
            .anyMatch(toolCall -> !Boolean.TRUE.equals(toolCall.metadata().get("complete")));
    }

    private TurnState failedState(
        String turnId,
        String sessionId,
        ContextSnapshot context,
        List<AgentMessage> newMessages,
        int toolRound
    ) {
        TurnState state = new TurnState(
            turnId,
            sessionId,
            context,
            List.copyOf(newMessages),
            toolRound,
            TurnStatus.FAILED
        );
        ports.eventBus().publish(new TurnEndEvent(sessionId, turnId, TurnStatus.FAILED.name(), clock.instant()));
        return state;
    }

    private TurnState continueFromAssistant(
        String turnId,
        String sessionId,
        ContextSnapshot context,
        Optional<String> contextLeafId,
        AgentMessage assistant,
        List<AgentMessage> newMessages,
        int currentToolRound,
        int maxToolRounds,
        BooleanSupplier abortSignal
    ) {
        int toolRound = currentToolRound;
        String leafId = contextLeafId.orElse(ports.sessionManager().currentView().leafId());
        while (!abortSignal.getAsBoolean()) {
            if (hasIncompleteToolCalls(assistant)) {
                AgentMessage error = messageFactory.errorMessage(
                    ids.newMessageId(),
                    "incomplete-tool-call",
                    "模型返回的工具调用参数未完成，已终止本轮执行。"
                );
                appendNewMessage(sessionId, error);
                newMessages.add(error);
                return failedState(turnId, sessionId, context, newMessages, toolRound);
            }
            List<ToolUseRequest> toolRequests = toolCallMapper.requestsFrom(assistant);
            if (toolRequests.isEmpty()) {
                break;
            }
            if (toolRound >= maxToolRounds) {
                AgentMessage error = messageFactory.errorMessage(
                    ids.newMessageId(),
                    "max-tool-rounds-exceeded",
                    "已达到工具调用轮数上限 " + maxToolRounds + "，终止本轮执行以避免无限循环。"
                );
                appendNewMessage(sessionId, error);
                newMessages.add(error);
                return failedState(turnId, sessionId, context, newMessages, toolRound);
            }
            toolRound++;
            for (ToolUseRequest toolRequest : toolRequests) {
                try {
                    ToolResult<?> toolResult = executeTool(sessionId, toolRequest, context);
                    leafId = appendToolResults(sessionId, List.of(toolResult), newMessages);
                    context = buildContext(sessionId, Optional.of(leafId), abortSignal);
                } catch (PendingToolPermissionException pending) {
                    return waitingPermissionState(
                        turnId,
                        sessionId,
                        context,
                        Optional.of(leafId),
                        newMessages,
                        toolRound,
                        maxToolRounds,
                        pending.pendingPermission()
                    );
                }
            }
            assistant = runModel(sessionId, context, abortSignal);
            leafId = appendStartedMessage(sessionId, assistant);
            newMessages.add(assistant);
            if (isAssistantError(assistant, abortSignal)) {
                return failedState(turnId, sessionId, context, newMessages, toolRound);
            }
        }

        TurnStatus status = abortSignal.getAsBoolean() ? TurnStatus.ABORTED : TurnStatus.COMPLETED;
        TurnState state = new TurnState(turnId, sessionId, context, List.copyOf(newMessages), toolRound, status);
        if (status == TurnStatus.COMPLETED) {
            extractMemorySafely(state);
        }
        ports.eventBus().publish(new TurnEndEvent(sessionId, turnId, status.name(), clock.instant()));
        return state;
    }

    private TurnState waitingPermissionState(
        String turnId,
        String sessionId,
        ContextSnapshot context,
        Optional<String> contextLeafId,
        List<AgentMessage> newMessages,
        int toolRound,
        int maxToolRounds,
        PendingPermission pendingPermission
    ) {
        appendPermissionPending(sessionId, pendingPermission, contextLeafId, toolRound, maxToolRounds);
        ports.eventBus().publish(new PermissionRequestEvent(
            sessionId,
            pendingPermission.toolUseId(),
            pendingPermission.toolName(),
            pendingPermission.renderedToolUse(),
            pendingPermission.message(),
            pendingPermission.decision(),
            clock.instant()
        ));
        return new TurnState(
            turnId,
            sessionId,
            context,
            List.copyOf(newMessages),
            toolRound,
            TurnStatus.WAITING_PERMISSION,
            Optional.of(pendingPermission)
        );
    }

    private ContextSnapshot buildContext(TurnRequest request, Optional<String> leafEntryId) {
        return buildContext(request.sessionId(), leafEntryId, request.abortSignal()::aborted);
    }

    private ContextSnapshot buildContext(String sessionId, Optional<String> leafEntryId, BooleanSupplier abortSignal) {
        ContextBuildRequest contextBuildRequest = new ContextBuildRequest(
            sessionId,
            leafEntryId,
            // NOTE: lypi-resource 负责从 cwd 探索 project root 和资源层级；agent-core 只传入启动层确定的 cwd 起点。
            ports.cwd(),
            true
        );
        ContextAssembly assembly = ports.contextAssembler().build(contextBuildRequest);
        CompactionDecision compaction = ports.compactionCoordinator().preflight(new CompactionRequest(
            sessionId,
            leafEntryId,
            ports.cwd(),
            contextBuildRequest,
            assembly,
            abortSignal::getAsBoolean
        ));
        return compaction.context();
    }

    private AgentMessage runModel(TurnRequest request, ContextSnapshot context) {
        return runModel(request.sessionId(), context, request.abortSignal()::aborted);
    }

    private AgentMessage runModel(String sessionId, ContextSnapshot context, BooleanSupplier abortSignal) {
        AssistantStreamAccumulator accumulator = new AssistantStreamAccumulator(clock);
        Optional<String> startedAssistantMessageId = Optional.empty();
        try (AssistantEventStream stream = ports.aiProvider().stream(context, abortSignal::getAsBoolean)) {
            for (AssistantStreamEvent event : stream) {
                accumulator.accept(event);
                if (event instanceof AssistantStart start) {
                    publishMessageStart(sessionId, start.messageId());
                    startedAssistantMessageId = Optional.of(start.messageId());
                }
                if (event instanceof TextDelta delta) {
                    ports.eventBus().publish(new MessageDeltaEvent(sessionId, currentAssistantId(accumulator), delta.text(), clock.instant()));
                }
                if (abortSignal.getAsBoolean()) {
                    break;
                }
            }
        } catch (RuntimeException failure) {
            startedAssistantMessageId.ifPresent(messageId -> publishMessageEnd(sessionId, messageId));
            throw failure;
        }

        return accumulator.toMessage(ids.newMessageId(), abortSignal.getAsBoolean());
    }

    private ToolResult<?> executeTool(String sessionId, ToolUseRequest toolRequest, ContextSnapshot context) {
        ensureToolRuntimeCwdMatches();
        ports.eventBus().publish(new ToolStartEvent(sessionId, toolRequest.toolUseId(), toolRequest.toolName(), clock.instant()));
        boolean endPublished = false;
        try {
            List<ToolResult<?>> results = ports.toolRuntime().execute(List.of(toolRequest), context);
            if (results.size() != 1) {
                ports.eventBus().publish(new ToolEndEvent(sessionId, toolRequest.toolUseId(), true, clock.instant()));
                endPublished = true;
                throw new IllegalStateException(
                    "Tool runtime returned " + results.size() + " result(s) for 1 request(s)"
                );
            }
            ToolResult<?> result = results.getFirst();
            ports.eventBus().publish(new ToolEndEvent(sessionId, toolRequest.toolUseId(), result.isError(), clock.instant()));
            endPublished = true;
            return result;
        } catch (PendingToolPermissionException pending) {
            throw pending;
        } catch (RuntimeException failure) {
            if (!endPublished) {
                ports.eventBus().publish(new ToolEndEvent(sessionId, toolRequest.toolUseId(), true, clock.instant()));
            }
            throw failure;
        }
    }

    private ToolResult<?> resumeTool(
        String sessionId,
        PendingPermission pendingPermission,
        ContextSnapshot context,
        PermissionResponse response
    ) {
        ensureToolRuntimeCwdMatches();
        boolean error = true;
        try {
            ToolResult<?> result = ports.toolRuntime().resume(pendingPermission.request(), context, response);
            error = result.isError();
            return result;
        } finally {
            ports.eventBus().publish(new ToolEndEvent(sessionId, pendingPermission.toolUseId(), error, clock.instant()));
        }
    }

    private ToolResult<?> deniedToolResult(PermissionResponse response, PendingPermission pendingPermission) {
        String message = response.feedback().orElse(pendingPermission.message());
        return new ToolResult<>(
            message,
            true,
            List.of(messageFactory.toolResultMessage(ids.newMessageId(), response.toolUseId(), "权限请求未获允许: " + message, true)),
            Optional.empty()
        );
    }

    private String appendToolResults(String sessionId, List<ToolResult<?>> toolResults, List<AgentMessage> newMessages) {
        String leafId = ports.sessionManager().currentView().leafId();
        for (ToolResult<?> toolResult : toolResults) {
            for (AgentMessage toolMessage : toolResult.newMessages()) {
                leafId = appendNewMessage(sessionId, toolMessage);
                newMessages.add(toolMessage);
            }
        }
        return leafId;
    }

    private void ensureToolRuntimeCwdMatches() {
        Path agentCwd = ports.cwd();
        Path toolCwd = ports.toolRuntime().cwd().toAbsolutePath().normalize();
        if (!agentCwd.equals(toolCwd)) {
            throw new IllegalStateException("工具运行目录不一致: agent cwd=" + agentCwd + ", tool cwd=" + toolCwd);
        }
    }

    private String appendNewMessage(String sessionId, AgentMessage message) {
        publishMessageStart(sessionId, message.id());
        return appendStartedMessage(sessionId, message);
    }

    private String appendStartedMessage(String sessionId, AgentMessage message) {
        String leafId = ports.sessionManager().appendMessage(message).leafId();
        publishMessageEnd(sessionId, message.id());
        return leafId;
    }

    private String appendPermissionPending(
        String sessionId,
        PendingPermission pendingPermission,
        Optional<String> contextLeafId,
        int currentToolRound,
        int maxToolRounds
    ) {
        String parentId = contextLeafId.orElse(ports.sessionManager().currentView().leafId());
        contextLeafId.ifPresent(ports.sessionManager()::switchLeaf);
        return ports.sessionManager().append(new PermissionPendingEntry(
            ids.newEntryId(),
            parentId,
            pendingPermission,
            parentId,
            currentToolRound,
            maxToolRounds,
            clock.instant()
        )).leafId();
    }

    private String appendPermissionDecision(String sessionId, PermissionResponse response) {
        return ports.sessionManager().append(new PermissionDecisionEntry(
            ids.newEntryId(),
            ports.sessionManager().currentView().leafId(),
            response,
            clock.instant()
        )).leafId();
    }

    private Optional<PermissionPendingEntry> findPendingPermission(PermissionResponse response) {
        SessionHandle handle = ports.sessionManager().openOrCreate(response.sessionId());
        List<PermissionDecisionEntry> decisions = handle.byId().values().stream()
            .filter(PermissionDecisionEntry.class::isInstance)
            .map(PermissionDecisionEntry.class::cast)
            .toList();
        return handle.byId().values().stream()
            .filter(PermissionPendingEntry.class::isInstance)
            .map(PermissionPendingEntry.class::cast)
            .filter(entry -> matches(entry.pendingPermission(), response))
            .filter(entry -> decisions.stream().noneMatch(decision -> matches(decision.response(), response)))
            .reduce((first, second) -> second);
    }

    private boolean matches(PendingPermission pending, PermissionResponse response) {
        return pending.turnId().equals(response.turnId())
            && pending.toolUseId().equals(response.toolUseId());
    }

    private boolean matches(PermissionResponse existing, PermissionResponse response) {
        return existing.turnId().equals(response.turnId())
            && existing.toolUseId().equals(response.toolUseId());
    }

    private PermissionDecision decisionFromResponse(PermissionResponse response, PermissionDecision originalDecision) {
        return new PermissionDecision(
            response.behavior(),
            originalDecision.reason(),
            response.feedback().orElse(originalDecision.message()),
            response.permissionUpdate().or(() -> originalDecision.suggestedUpdate()),
            originalDecision.metadata()
        );
    }

    private String currentAssistantId(AssistantStreamAccumulator accumulator) {
        return accumulator.messageId().orElse("streaming");
    }

    private void publishMessageStart(String sessionId, String messageId) {
        ports.eventBus().publish(new MessageStartEvent(sessionId, messageId, clock.instant()));
    }

    private void publishMessageEnd(String sessionId, String messageId) {
        ports.eventBus().publish(new MessageEndEvent(sessionId, messageId, clock.instant()));
    }
}
