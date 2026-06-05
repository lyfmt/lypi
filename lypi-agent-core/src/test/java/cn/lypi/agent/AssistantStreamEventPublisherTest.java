package cn.lypi.agent;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.EventConsumer;
import cn.lypi.contracts.event.EventFilter;
import cn.lypi.contracts.event.EventSubscription;
import cn.lypi.contracts.event.MessageDeltaEvent;
import cn.lypi.contracts.event.MessageEndEvent;
import cn.lypi.contracts.event.MessageStartEvent;
import cn.lypi.contracts.model.AssistantDone;
import cn.lypi.contracts.model.AssistantError;
import cn.lypi.contracts.model.AssistantEventStream;
import cn.lypi.contracts.model.AssistantStart;
import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.model.AssistantStreamResult;
import cn.lypi.contracts.model.TextDelta;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AssistantStreamEventPublisherTest {
    @Test
    void publishesMappedMessageEventsToEventBus() {
        RecordingEventBus eventBus = new RecordingEventBus();
        AssistantStreamEventPublisher publisher = new AssistantStreamEventPublisher(
            "ses_01",
            eventBus,
            Instant.parse("2026-06-01T12:00:00Z")
        );
        TestAssistantEventStream stream = new TestAssistantEventStream(List.of(
            new AssistantStart("msg_01"),
            new TextDelta("hello"),
            new AssistantDone(Optional.empty(), Optional.of("stop"))
        ));

        publisher.publish(stream);

        assertThat(eventBus.events())
            .hasExactlyElementsOfTypes(MessageStartEvent.class, MessageDeltaEvent.class, MessageEndEvent.class);
        assertThat(((MessageDeltaEvent) eventBus.events().get(1)).delta()).isEqualTo("hello");
        assertThat(stream.closed()).isTrue();
    }

    @Test
    void closesAssistantStreamWhenPublishingFails() {
        FailingEventBus eventBus = new FailingEventBus();
        AssistantStreamEventPublisher publisher = new AssistantStreamEventPublisher(
            "ses_01",
            eventBus,
            Instant.parse("2026-06-01T12:00:00Z")
        );
        TestAssistantEventStream stream = new TestAssistantEventStream(List.of(
            new AssistantStart("msg_01"),
            new TextDelta("hello")
        ));

        Assertions.assertThrows(IllegalStateException.class, () -> publisher.publish(stream));

        assertThat(stream.closed()).isTrue();
    }

    private static final class RecordingEventBus implements EventBus {
        private final List<AgentEvent> events = new ArrayList<>();

        private List<AgentEvent> events() {
            return events;
        }

        @Override
        public void publish(AgentEvent event) {
            events.add(event);
        }

        @Override
        public EventSubscription subscribe(EventFilter filter, EventConsumer consumer) {
            throw new UnsupportedOperationException("not used");
        }
    }

    private static final class FailingEventBus implements EventBus {
        @Override
        public void publish(AgentEvent event) {
            throw new IllegalStateException("publish failed");
        }

        @Override
        public EventSubscription subscribe(EventFilter filter, EventConsumer consumer) {
            throw new UnsupportedOperationException("not used");
        }
    }

    private static final class TestAssistantEventStream implements AssistantEventStream {
        private final List<AssistantStreamEvent> events;
        private boolean closed;

        private TestAssistantEventStream(List<AssistantStreamEvent> events) {
            this.events = events;
        }

        @Override
        public AssistantStreamResult result() {
            return new AssistantStreamResult(
                "msg_01",
                events,
                events.stream()
                    .filter(AssistantDone.class::isInstance)
                    .map(AssistantDone.class::cast)
                    .findFirst()
                    .flatMap(AssistantDone::usage),
                events.stream()
                    .filter(AssistantDone.class::isInstance)
                    .map(AssistantDone.class::cast)
                    .findFirst()
                    .flatMap(AssistantDone::stopReason),
                events.stream().anyMatch(AssistantDone.class::isInstance),
                false,
                events.stream()
                    .filter(AssistantError.class::isInstance)
                    .map(AssistantError.class::cast)
                    .findFirst()
            );
        }

        @Override
        public void close() {
            closed = true;
        }

        private boolean closed() {
            return closed;
        }

        @Override
        public Iterator<AssistantStreamEvent> iterator() {
            return events.iterator();
        }
    }
}
