package cn.lypi.agent;

import cn.lypi.agent.compact.CompactionDecision;
import cn.lypi.agent.compact.CompactionRequest;
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
import cn.lypi.contracts.event.TurnEndEvent;
import cn.lypi.contracts.event.TurnStartEvent;
import cn.lypi.contracts.event.ToolEndEvent;
import cn.lypi.contracts.event.ToolStartEvent;
import cn.lypi.contracts.model.AssistantEventStream;
import cn.lypi.contracts.model.AssistantStart;
import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.model.TextDelta;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
        int toolRound = 0;
        try {
            context = buildContext(request, Optional.of(contextLeafId));
            AgentMessage assistant = runModel(request, context);
            contextLeafId = appendStartedMessage(request.sessionId(), assistant);
            newMessages.add(assistant);
            if (isAssistantError(assistant, request)) {
                return failedState(turnId, request.sessionId(), context, newMessages, toolRound);
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
                    return failedState(turnId, request.sessionId(), context, newMessages, toolRound);
                }
                List<ToolUseRequest> toolRequests = toolCallMapper.requestsFrom(assistant);
                if (toolRequests.isEmpty()) {
                    break;
                }
                if (toolRound >= request.maxToolRounds()) {
                    AgentMessage error = messageFactory.errorMessage(
                        ids.newMessageId(),
                        "max-tool-rounds-exceeded",
                        "已达到工具调用轮数上限 " + request.maxToolRounds() + "，终止本轮执行以避免无限循环。"
                    );
                    appendNewMessage(request.sessionId(), error);
                    newMessages.add(error);
                    return failedState(turnId, request.sessionId(), context, newMessages, toolRound);
                }
                toolRound++;
                List<ToolResult<?>> toolResults = executeTools(request.sessionId(), toolRequests, context);
                for (ToolResult<?> toolResult : toolResults) {
                    for (AgentMessage toolMessage : toolResult.newMessages()) {
                        contextLeafId = appendNewMessage(request.sessionId(), toolMessage);
                        newMessages.add(toolMessage);
                    }
                }
                context = buildContext(request, Optional.of(contextLeafId));
                assistant = runModel(request, context);
                contextLeafId = appendStartedMessage(request.sessionId(), assistant);
                newMessages.add(assistant);
                if (isAssistantError(assistant, request)) {
                    return failedState(turnId, request.sessionId(), context, newMessages, toolRound);
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
            return failedState(turnId, request.sessionId(), context, newMessages, toolRound);
        }

        TurnStatus status = request.abortSignal().aborted() ? TurnStatus.ABORTED : TurnStatus.COMPLETED;
        TurnState state = new TurnState(turnId, request.sessionId(), context, List.copyOf(newMessages), toolRound, status);
        if (status == TurnStatus.COMPLETED) {
            extractMemorySafely(state);
        }
        ports.eventBus().publish(new TurnEndEvent(request.sessionId(), turnId, status.name(), clock.instant()));
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

    private ContextSnapshot buildContext(TurnRequest request, Optional<String> leafEntryId) {
        ContextBuildRequest contextBuildRequest = new ContextBuildRequest(
            request.sessionId(),
            leafEntryId,
            // NOTE: lypi-resource 负责从 cwd 探索 project root 和资源层级；agent-core 只传入启动层确定的 cwd 起点。
            ports.cwd(),
            true
        );
        ContextAssembly assembly = ports.contextAssembler().build(contextBuildRequest);
        CompactionDecision compaction = ports.compactionCoordinator().preflight(new CompactionRequest(
            request.sessionId(),
            leafEntryId,
            ports.cwd(),
            contextBuildRequest,
            assembly,
            request.abortSignal()
        ));
        return compaction.context();
    }

    private AgentMessage runModel(TurnRequest request, ContextSnapshot context) {
        AssistantStreamAccumulator accumulator = new AssistantStreamAccumulator(clock);
        Optional<String> startedAssistantMessageId = Optional.empty();
        try (AssistantEventStream stream = ports.aiProvider().stream(context, request.abortSignal())) {
            for (AssistantStreamEvent event : stream) {
                accumulator.accept(event);
                if (event instanceof AssistantStart start) {
                    publishMessageStart(request.sessionId(), start.messageId());
                    startedAssistantMessageId = Optional.of(start.messageId());
                }
                if (event instanceof TextDelta delta) {
                    ports.eventBus().publish(new MessageDeltaEvent(request.sessionId(), currentAssistantId(accumulator), delta.text(), clock.instant()));
                }
                if (request.abortSignal().aborted()) {
                    break;
                }
            }
        } catch (RuntimeException failure) {
            startedAssistantMessageId.ifPresent(messageId -> publishMessageEnd(request.sessionId(), messageId));
            throw failure;
        }

        return accumulator.toMessage(ids.newMessageId(), request.abortSignal().aborted());
    }

    private List<ToolResult<?>> executeTools(String sessionId, List<ToolUseRequest> toolRequests, ContextSnapshot context) {
        ensureToolRuntimeCwdMatches();
        for (ToolUseRequest toolRequest : toolRequests) {
            ports.eventBus().publish(new ToolStartEvent(sessionId, toolRequest.toolUseId(), toolRequest.toolName(), clock.instant()));
        }
        List<ToolResult<?>> results;
        boolean toolEndErrorsPublished = false;
        try {
            results = ports.toolRuntime().execute(toolRequests, context);
            if (results.size() != toolRequests.size()) {
                publishToolEndErrors(sessionId, toolRequests);
                toolEndErrorsPublished = true;
                throw new IllegalStateException(
                    "Tool runtime returned " + results.size() + " result(s) for " + toolRequests.size() + " request(s)"
                );
            }
        } catch (RuntimeException failure) {
            if (!toolEndErrorsPublished) {
                publishToolEndErrors(sessionId, toolRequests);
            }
            throw failure;
        }
        for (int index = 0; index < toolRequests.size(); index++) {
            ToolUseRequest request = toolRequests.get(index);
            ports.eventBus().publish(new ToolEndEvent(sessionId, request.toolUseId(), results.get(index).isError(), clock.instant()));
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

    private void publishToolEndErrors(String sessionId, List<ToolUseRequest> toolRequests) {
        for (ToolUseRequest request : toolRequests) {
            ports.eventBus().publish(new ToolEndEvent(sessionId, request.toolUseId(), true, clock.instant()));
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
