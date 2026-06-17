package cn.lypi.agent;

import cn.lypi.agent.compact.CompactionDecision;
import cn.lypi.agent.compact.CompactionRequest;
import cn.lypi.agent.compact.ToolMicroCompactRequest;
import cn.lypi.agent.compact.ToolMicroCompactResult;
import cn.lypi.contracts.agent.TurnRequest;
import cn.lypi.contracts.agent.TurnState;
import cn.lypi.contracts.agent.TurnStatus;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlockKind;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.event.ErrorEvent;
import cn.lypi.contracts.event.TurnStartEvent;
import cn.lypi.contracts.model.AssistantEventStream;
import cn.lypi.contracts.model.AssistantError;
import cn.lypi.contracts.model.AssistantStart;
import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.model.ProviderRetryNotice;
import cn.lypi.contracts.model.TextDelta;
import cn.lypi.contracts.model.ThinkingDelta;
import cn.lypi.contracts.model.ToolCallDelta;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseRequest;
import cn.lypi.contracts.runtime.ToolRuntimeInvocation;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class DefaultTurnExecutor implements TurnExecutor {
    private final AgentCoreRuntimePorts ports;
    private final TurnIds ids;
    private final Clock clock;
    private final AgentMessageFactory messageFactory;
    private final ToolCallMapper toolCallMapper;
    private final AgentCoreExceptionHandler exceptionHandler;
    private final ContextBudgetEstimator budgetEstimator;
    private final TurnContinuationGuard continuationGuard;
    private final TurnEventPublisher eventPublisher;

    public DefaultTurnExecutor(AgentCoreRuntimePorts ports, TurnIds ids, Clock clock) {
        this.ports = ports;
        this.ids = ids;
        this.clock = clock;
        this.messageFactory = new AgentMessageFactory(clock);
        this.toolCallMapper = new ToolCallMapper();
        this.exceptionHandler = new AgentCoreExceptionHandler(ports.eventBus(), messageFactory, clock);
        this.budgetEstimator = new ContextBudgetEstimator();
        this.continuationGuard = new TurnContinuationGuard(ports.sessionManager());
        this.eventPublisher = new TurnEventPublisher(ports.eventBus(), clock);
    }

    @Override
    public TurnState execute(TurnRequest request) {
        String turnId = ids.newTurnId();
        List<AgentMessage> newMessages = new ArrayList<>();
        ports.sessionManager().openOrCreate(request.sessionId());
        request.parentEntryId().ifPresent(parentEntryId -> ports.sessionManager().switchLeaf(parentEntryId));
        Instant startedAt = clock.instant();
        ports.eventBus().publish(new TurnStartEvent(request.sessionId(), turnId, startedAt, startedAt));
        Optional<String> unsafeReason = continuationGuard.unsafeContinuationReason(currentLeafId());
        if (unsafeReason.isPresent()) {
            ports.eventBus().publish(new ErrorEvent(
                request.sessionId(),
                unsafeReason.orElseThrow(),
                "当前分支停在 assistant 工具调用消息上，不能直接追加用户消息。请选择上一条用户消息或工具结果之后继续。",
                clock.instant()
            ));
            return failedState(turnId, request.sessionId(), null, List.of(), 0, startedAt);
        }

        AgentMessage user = messageFactory.userMessage(ids.newMessageId(), request.userInput());
        String contextLeafId = appendNewMessage(request.sessionId(), user);
        newMessages.add(user);

        ContextSnapshot context = null;
        int toolRound = 0;
        try {
            context = buildContext(request, Optional.of(contextLeafId));
            AgentMessage assistant = runModel(request, context);
            contextLeafId = appendStartedMessage(request.sessionId(), assistant);
            newMessages.add(assistant);
            if (isAssistantError(assistant, request)) {
                return failedState(turnId, request.sessionId(), context, newMessages, toolRound, startedAt);
            }

            while (!request.abortSignal().aborted()) {
                if (hasIncompleteToolCalls(assistant)) {
                    AgentMessage error = messageFactory.errorMessage(
                        ids.newMessageId(),
                        "incomplete-tool-call",
                        "模型返回的工具调用参数未完成，已终止本轮执行。"
                    );
                    appendNewMessage(request.sessionId(), error);
                    newMessages.add(error);
                    return failedState(turnId, request.sessionId(), context, newMessages, toolRound, startedAt);
                }
                List<ToolUseRequest> toolRequests = toolCallMapper.requestsFrom(assistant);
                if (toolRequests.isEmpty()) {
                    break;
                }
                toolRound++;
                List<ToolResult<?>> toolResults = executeTools(
                    request.sessionId(),
                    turnId,
                    contextLeafId,
                    toolRequests,
                    context
                );
                for (ToolResult<?> toolResult : toolResults) {
                    for (AgentMessage toolMessage : toolResult.newMessages()) {
                        AgentMessage pendingToolMessage = ToolResultMessageMarker.markPendingToolOutput(toolMessage);
                        contextLeafId = appendNewMessage(request.sessionId(), pendingToolMessage);
                        newMessages.add(pendingToolMessage);
                    }
                }
                context = buildContext(request, Optional.of(contextLeafId));
                assistant = runModel(request, context);
                contextLeafId = appendStartedMessage(request.sessionId(), assistant);
                newMessages.add(assistant);
                if (isAssistantError(assistant, request)) {
                    return failedState(turnId, request.sessionId(), context, newMessages, toolRound, startedAt);
                }
            }
        } catch (RuntimeException failure) {
            AgentCoreExceptionHandler.Failure handled = exceptionHandler.handle(
                request.sessionId(),
                ids.newMessageId(),
                failure
            );
            appendNewMessage(request.sessionId(), handled.message());
            newMessages.add(handled.message());
            return failedState(turnId, request.sessionId(), context, newMessages, toolRound, startedAt);
        }

        TurnStatus status = request.abortSignal().aborted() ? TurnStatus.ABORTED : TurnStatus.COMPLETED;
        TurnState state = new TurnState(turnId, request.sessionId(), context, List.copyOf(newMessages), toolRound, status);
        if (status == TurnStatus.COMPLETED) {
            extractMemorySafely(state);
        }
        eventPublisher.publishTurnEnd(request.sessionId(), turnId, status, startedAt, toolRound);
        return state;
    }

    private void extractMemorySafely(TurnState state) {
        try {
            ports.memoryExtractionWorker().extractAfterTurn(state);
        } catch (RuntimeException ignored) {
            // NOTE: 记忆提取是 turn 后置任务，失败不得改变 turn 结果。
        }
    }

    private boolean isAssistantError(AgentMessage assistant, TurnRequest request) {
        return !request.abortSignal().aborted()
            && assistant.content().stream().anyMatch(block -> block.kind() == ContentBlockKind.ERROR);
    }

    private boolean hasIncompleteToolCalls(AgentMessage assistant) {
        return assistant.content().stream()
            .filter(ToolCallContentBlock.class::isInstance)
            .map(ToolCallContentBlock.class::cast)
            .anyMatch(toolCall -> !Boolean.TRUE.equals(toolCall.metadata().get("complete")));
    }

    private String currentLeafId() {
        return ports.sessionManager().currentView().leafId();
    }

    private TurnState failedState(
        String turnId,
        String sessionId,
        ContextSnapshot context,
        List<AgentMessage> newMessages,
        int toolRound,
        Instant startedAt
    ) {
        TurnState state = new TurnState(
            turnId,
            sessionId,
            context,
            List.copyOf(newMessages),
            toolRound,
            TurnStatus.FAILED
        );
        eventPublisher.publishTurnEnd(sessionId, turnId, TurnStatus.FAILED, startedAt, toolRound);
        return state;
    }

    private ContextSnapshot buildContext(TurnRequest request, Optional<String> leafEntryId) {
        ContextBuildRequest contextBuildRequest = new ContextBuildRequest(
            request.sessionId(),
            leafEntryId,
            // NOTE: lypi-resource 负责从 cwd 探索 project root 和资源层级；agent-core 只传入启动层确定的 cwd 起点。
            ports.cwd(),
            true,
            request.skillMentions()
        );
        ContextAssembly assembly = ports.contextAssembler().build(contextBuildRequest);
        ToolMicroCompactResult microCompact = ports.toolMicroCompactor().compact(new ToolMicroCompactRequest(
            request.sessionId(),
            leafEntryId,
            assembly.branchEntryIds(),
            assembly.snapshot(),
            ports.toolRuntime().snapshot()
        ));
        ContextSnapshot microCompactedContext = microCompact.projectedToolUseIds().isEmpty()
            ? microCompact.context()
            : reestimateBudget(microCompact.context(), assembly.snapshot().budget());
        ContextAssembly microCompactedAssembly = new ContextAssembly(
            microCompactedContext,
            assembly.resources(),
            assembly.branchEntryIds(),
            assembly.appliedCompactionEntryIds(),
            assembly.replacements(),
            microCompactedContext.budget().estimatedContextTokens() > microCompactedContext.budget().autoCompactThreshold()
        );
        CompactionDecision compaction = ports.compactionCoordinator().preflight(new CompactionRequest(
            request.sessionId(),
            leafEntryId,
            ports.cwd(),
            contextBuildRequest,
            microCompactedAssembly,
            request.abortSignal()
        ));
        if (compaction.compacted()) {
            ports.toolMicroCompactor().reset();
        }
        return compaction.context();
    }

    private ContextSnapshot reestimateBudget(ContextSnapshot context, ContextBudget previousBudget) {
        ContextBudget estimated = budgetEstimator.estimate(context);
        ContextBudget budget = new ContextBudget(
            estimated.estimatedContextTokens(),
            previousBudget.effectiveContextWindow(),
            previousBudget.autoCompactThreshold(),
            previousBudget.turnOutputBudget(),
            previousBudget.toolResultBudget(),
            previousBudget.totalInputTokens(),
            previousBudget.totalOutputTokens(),
            previousBudget.estimatedCost()
        );
        return new ContextSnapshot(
            context.systemPrompt(),
            context.messages(),
            context.model(),
            context.thinkingLevel(),
            context.mode(),
            context.permissionRuntimeState(),
            budget
        );
    }

    private AgentMessage runModel(TurnRequest request, ContextSnapshot context) {
        AssistantStreamAccumulator accumulator = new AssistantStreamAccumulator(clock);
        String sessionId = request.sessionId();
        final boolean[] assistantStarted = {false};
        final MessageKind[] startedKind = {MessageKind.TEXT};
        Optional<ProviderRetryNotice> pendingRetry = Optional.empty();
        ProviderConversationStateHolder providerConversationState = new ProviderConversationStateHolder();
        try (AssistantEventStream stream = ports.aiProvider().stream(
            context,
            ports.toolRuntime().snapshot(),
            new cn.lypi.contracts.runtime.AiStreamOptions(request.sessionId()),
            request.abortSignal()
        )) {
            for (AssistantStreamEvent event : stream) {
                if (event instanceof ProviderRetryNotice notice) {
                    pendingRetry.ifPresent(previous -> eventPublisher.publishRetryEnd(request.sessionId(), previous, false));
                    eventPublisher.publishRetryStart(request.sessionId(), notice);
                    pendingRetry = Optional.of(notice);
                    continue;
                }
                if (pendingRetry.isPresent()) {
                    ProviderRetryNotice notice = pendingRetry.get();
                    eventPublisher.publishRetryEnd(request.sessionId(), notice, !(event instanceof cn.lypi.contracts.model.AssistantError));
                    pendingRetry = Optional.empty();
                }
                accumulator.accept(event);
                if (event instanceof TextDelta delta) {
                    String messageId = currentAssistantId(accumulator);
                    ensureAssistantMessageStart(sessionId, messageId, MessageKind.TEXT, assistantStarted, startedKind);
                    eventPublisher.publishAssistantDelta(eventPublisher.assistantDelta(
                        sessionId,
                        messageId,
                        MessageKind.TEXT,
                        TurnEventPublisher.textBlockId(messageId),
                        ContentBlockKind.TEXT,
                        delta.text(),
                        false,
                        Map.of()
                    ));
                }
                if (event instanceof ThinkingDelta delta) {
                    String messageId = currentAssistantId(accumulator);
                    ensureAssistantMessageStart(sessionId, messageId, MessageKind.TEXT, assistantStarted, startedKind);
                    eventPublisher.publishAssistantDelta(eventPublisher.assistantDelta(
                        sessionId,
                        messageId,
                        MessageKind.THINKING,
                        TurnEventPublisher.thinkingBlockId(messageId),
                        ContentBlockKind.THINKING,
                        delta.text(),
                        false,
                        Map.of()
                    ));
                }
                if (event instanceof ToolCallDelta delta) {
                    String messageId = currentAssistantId(accumulator);
                    ensureAssistantMessageStart(sessionId, messageId, MessageKind.TOOL_CALL, assistantStarted, startedKind);
                    eventPublisher.publishAssistantDelta(eventPublisher.assistantDelta(
                        sessionId,
                        messageId,
                        MessageKind.TOOL_CALL,
                        TurnEventPublisher.toolCallBlockId(messageId, delta.toolUseId()),
                        ContentBlockKind.TOOL_CALL,
                        "",
                        delta.complete(),
                        eventPublisher.toolCallDeltaMetadata(delta)
                    ));
                }
                if (event instanceof AssistantError error) {
                    String messageId = currentAssistantId(accumulator);
                    ensureAssistantMessageStart(sessionId, messageId, MessageKind.ERROR, assistantStarted, startedKind);
                    eventPublisher.publishAssistantDelta(eventPublisher.assistantDelta(
                        sessionId,
                        messageId,
                        MessageKind.ERROR,
                        TurnEventPublisher.errorBlockId(messageId),
                        ContentBlockKind.ERROR,
                        error.message(),
                        true,
                        Map.of("errorId", error.errorId())
                    ));
                }
                if (request.abortSignal().aborted()) {
                    pendingRetry.ifPresent(notice -> eventPublisher.publishRetryEnd(request.sessionId(), notice, false));
                    pendingRetry = Optional.empty();
                    break;
                }
            }
            providerConversationState.value = stream.result().providerConversationState();
        } catch (RuntimeException failure) {
            pendingRetry.ifPresent(notice -> eventPublisher.publishRetryEnd(sessionId, notice, false));
            accumulator.messageId()
                .ifPresent(messageId -> {
                    ensureAssistantMessageStart(sessionId, messageId, MessageKind.TEXT, assistantStarted, startedKind);
                    eventPublisher.publishAssistantMessageEnd(sessionId, messageId, startedKind[0]);
                });
            throw failure;
        }
        pendingRetry.ifPresent(notice -> eventPublisher.publishRetryEnd(request.sessionId(), notice, false));

        AgentMessage message = accumulator.toMessage(
            ids.newMessageId(),
            request.abortSignal().aborted(),
            providerConversationState.value
        );
        ensureAssistantMessageStart(sessionId, message.id(), message.kind(), assistantStarted, startedKind);
        return message;
    }

    private List<ToolResult<?>> executeTools(
        String sessionId,
        String turnId,
        String parentEntryId,
        List<ToolUseRequest> toolRequests,
        ContextSnapshot context
    ) {
        ensureToolRuntimeCwdMatches();
        List<ToolResult<?>> results;
        try {
            results = ports.toolRuntime().execute(
                toolRequests,
                context,
                new ToolRuntimeInvocation(sessionId, turnId, parentEntryId)
            );
            if (results.size() != toolRequests.size()) {
                throw new IllegalStateException(
                    "Tool runtime returned " + results.size() + " result(s) for " + toolRequests.size() + " request(s)"
                );
            }
        } catch (RuntimeException failure) {
            throw failure;
        }
        return results;
    }

    private void ensureToolRuntimeCwdMatches() {
        Path agentCwd = ports.cwd();
        Path toolCwd = ports.toolRuntime().cwd().toAbsolutePath().normalize();
        if (!agentCwd.equals(toolCwd)) {
            throw new IllegalStateException("工具运行目录不一致: agent cwd=" + agentCwd + ", tool cwd=" + toolCwd);
        }
    }

    private String appendNewMessage(String sessionId, AgentMessage message) {
        eventPublisher.publishMessageStart(sessionId, message);
        return appendStartedMessage(sessionId, message);
    }

    private String appendStartedMessage(String sessionId, AgentMessage message) {
        String leafId = ports.sessionManager().appendMessage(message).leafId();
        eventPublisher.publishMessageEnd(sessionId, message);
        return leafId;
    }

    private String currentAssistantId(AssistantStreamAccumulator accumulator) {
        return accumulator.messageId().orElse("streaming");
    }

    private void publishAssistantMessageStart(String sessionId, String messageId, MessageKind kind) {
        eventPublisher.publishAssistantMessageStart(sessionId, messageId, kind);
    }

    private void ensureAssistantMessageStart(
        String sessionId,
        String messageId,
        MessageKind kind,
        boolean[] assistantStarted,
        MessageKind[] startedKind
    ) {
        if (assistantStarted[0]) {
            return;
        }
        publishAssistantMessageStart(sessionId, messageId, kind);
        startedKind[0] = kind;
        assistantStarted[0] = true;
    }

    private static final class ProviderConversationStateHolder {
        private Optional<cn.lypi.contracts.model.ProviderConversationState> value = Optional.empty();
    }
}
