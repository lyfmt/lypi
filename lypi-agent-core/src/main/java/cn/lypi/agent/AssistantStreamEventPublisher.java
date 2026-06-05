package cn.lypi.agent;

import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.model.AssistantEventStream;
import cn.lypi.contracts.model.AssistantStreamEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class AssistantStreamEventPublisher {
    private final EventBus eventBus;
    private final AssistantStreamEventMessageMapper mapper;

    public AssistantStreamEventPublisher(String sessionId, EventBus eventBus, Clock clock) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus must not be null");
        this.mapper = new AssistantStreamEventMessageMapper(sessionId, clock);
    }

    AssistantStreamEventPublisher(String sessionId, EventBus eventBus, Instant fixedTimestamp) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus must not be null");
        this.mapper = new AssistantStreamEventMessageMapper(sessionId, fixedTimestamp);
    }

    public void publish(AssistantEventStream stream) {
        for (AssistantStreamEvent streamEvent : Objects.requireNonNull(stream, "stream must not be null")) {
            List<AgentEvent> events = mapper.map(streamEvent);
            for (AgentEvent event : events) {
                eventBus.publish(event);
            }
        }
    }
}
