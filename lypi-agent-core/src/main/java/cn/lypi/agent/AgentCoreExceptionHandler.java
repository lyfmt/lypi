package cn.lypi.agent;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.event.ErrorEvent;
import cn.lypi.contracts.event.EventBus;
import java.time.Clock;

public final class AgentCoreExceptionHandler {
    private final EventBus eventBus;
    private final AgentMessageFactory messageFactory;
    private final Clock clock;

    public AgentCoreExceptionHandler(EventBus eventBus, AgentMessageFactory messageFactory, Clock clock) {
        this.eventBus = eventBus;
        this.messageFactory = messageFactory;
        this.clock = clock;
    }

    public Failure handle(String sessionId, String messageId, RuntimeException failure) {
        String errorId = failure.getClass().getSimpleName();
        String message = failure.getMessage() == null ? errorId : failure.getMessage();
        eventBus.publish(new ErrorEvent(sessionId, errorId, message, clock.instant()));
        return new Failure(messageFactory.errorMessage(messageId, errorId, message), message);
    }

    public record Failure(AgentMessage message, String reason) {}
}
