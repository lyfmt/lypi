package cn.lypi.agent;

import cn.lypi.contracts.agent.TurnRequest;
import cn.lypi.contracts.agent.TurnState;
import cn.lypi.contracts.agent.TurnStatus;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.event.MessageDeltaEvent;
import cn.lypi.contracts.event.MessageEndEvent;
import cn.lypi.contracts.event.MessageStartEvent;
import cn.lypi.contracts.event.TurnEndEvent;
import cn.lypi.contracts.event.TurnStartEvent;
import cn.lypi.contracts.model.AssistantEventStream;
import cn.lypi.contracts.model.AssistantStart;
import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.model.TextDelta;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

public final class DefaultTurnExecutor implements TurnExecutor {
    private final AgentCoreRuntimePorts ports;
    private final TurnIds ids;
    private final Clock clock;
    private final AgentMessageFactory messageFactory;

    public DefaultTurnExecutor(AgentCoreRuntimePorts ports, TurnIds ids, Clock clock) {
        this.ports = ports;
        this.ids = ids;
        this.clock = clock;
        this.messageFactory = new AgentMessageFactory(clock);
    }

    @Override
    public TurnState execute(TurnRequest request) {
        String turnId = ids.newTurnId();
        List<AgentMessage> newMessages = new ArrayList<>();
        ports.sessionEngine().openOrCreate(request.sessionId());
        ports.eventBus().publish(new TurnStartEvent(request.sessionId(), turnId, clock.instant()));

        AgentMessage user = messageFactory.userMessage(ids.newMessageId(), request.userInput());
        publishMessageStart(request.sessionId(), user.id());
        ports.sessionEngine().appendMessage(user);
        publishMessageEnd(request.sessionId(), user.id());
        newMessages.add(user);

        ContextAssembly assembly = ports.contextAssembler().build(new ContextBuildRequest(
            request.sessionId(),
            request.parentEntryId(),
            Path.of("."),
            true
        ));
        CompactionDecision compaction = ports.compactionCoordinator().preflight(assembly.snapshot());
        ContextSnapshot context = compaction.context();

        AssistantStreamAccumulator accumulator = new AssistantStreamAccumulator(clock);
        try (AssistantEventStream stream = ports.aiProvider().stream(context, request.abortSignal())) {
            for (AssistantStreamEvent event : stream) {
                accumulator.accept(event);
                if (event instanceof AssistantStart start) {
                    publishMessageStart(request.sessionId(), start.messageId());
                }
                if (event instanceof TextDelta delta) {
                    ports.eventBus().publish(new MessageDeltaEvent(request.sessionId(), currentAssistantId(accumulator), delta.text(), clock.instant()));
                }
                if (request.abortSignal().aborted()) {
                    break;
                }
            }
        }

        AgentMessage assistant = accumulator.toMessage(ids.newMessageId(), request.abortSignal().aborted());
        ports.sessionEngine().appendMessage(assistant);
        publishMessageEnd(request.sessionId(), assistant.id());
        newMessages.add(assistant);

        TurnStatus status = request.abortSignal().aborted() ? TurnStatus.ABORTED : TurnStatus.COMPLETED;
        TurnState state = new TurnState(turnId, request.sessionId(), context, List.copyOf(newMessages), 0, status);
        if (status == TurnStatus.COMPLETED) {
            ports.memoryExtractionWorker().extractAfterTurn(state);
        }
        ports.eventBus().publish(new TurnEndEvent(request.sessionId(), turnId, status.name(), clock.instant()));
        return state;
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
