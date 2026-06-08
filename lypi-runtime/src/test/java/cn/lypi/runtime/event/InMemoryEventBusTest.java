package cn.lypi.runtime.event;

import cn.lypi.contracts.event.EventEnvelope;
import cn.lypi.contracts.event.EventFilter;
import cn.lypi.contracts.event.EventSubscription;
import cn.lypi.contracts.event.MessageStartEvent;
import cn.lypi.contracts.event.TurnStartEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryEventBusTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void publishAssignsEnvelopeIdsAndIncreasingSequence() {
        InMemoryEventBus bus = new InMemoryEventBus();
        List<EventEnvelope> envelopes = new ArrayList<>();
        bus.subscribe(anyEvent(), envelopes::add);

        bus.publish(new TurnStartEvent("session-1", "turn-1", NOW));
        bus.publish(new TurnStartEvent("session-1", "turn-2", NOW));

        assertThat(envelopes).extracting(EventEnvelope::eventId)
            .containsExactly("evt_1", "evt_2");
        assertThat(envelopes).extracting(EventEnvelope::sequence)
            .containsExactly(1L, 2L);
        assertThat(envelopes).extracting(EventEnvelope::sessionId)
            .containsExactly("session-1", "session-1");
    }

    @Test
    void subscribeFiltersBySessionAndEventTypeWithAndSemantics() {
        InMemoryEventBus bus = new InMemoryEventBus();
        List<EventEnvelope> envelopes = new ArrayList<>();
        bus.subscribe(
            new EventFilter(Optional.of("session-1"), Optional.of(TurnStartEvent.class)),
            envelopes::add
        );

        bus.publish(new TurnStartEvent("session-1", "turn-1", NOW));
        bus.publish(new TurnStartEvent("session-2", "turn-2", NOW));
        bus.publish(new MessageStartEvent("session-1", "msg-1", NOW));

        assertThat(envelopes).hasSize(1);
        assertThat(envelopes.getFirst().event()).isInstanceOf(TurnStartEvent.class);
        assertThat(envelopes.getFirst().sessionId()).isEqualTo("session-1");
    }

    @Test
    void deliversToSubscribersInRegistrationOrder() {
        InMemoryEventBus bus = new InMemoryEventBus();
        List<String> calls = new ArrayList<>();
        bus.subscribe(anyEvent(), ignored -> calls.add("first"));
        bus.subscribe(anyEvent(), ignored -> calls.add("second"));

        bus.publish(new TurnStartEvent("session-1", "turn-1", NOW));

        assertThat(calls).containsExactly("first", "second");
    }

    @Test
    void closeIsIdempotentAndStopsFutureDelivery() {
        InMemoryEventBus bus = new InMemoryEventBus();
        List<EventEnvelope> envelopes = new ArrayList<>();
        EventSubscription subscription = bus.subscribe(anyEvent(), envelopes::add);

        subscription.close();
        subscription.close();
        bus.publish(new TurnStartEvent("session-1", "turn-1", NOW));

        assertThat(envelopes).isEmpty();
        assertThat(bus.subscriberCount()).isZero();
    }

    @Test
    void consumerFailureDoesNotStopOtherConsumersOrPublisher() {
        InMemoryEventBus bus = new InMemoryEventBus();
        List<EventEnvelope> envelopes = new ArrayList<>();
        bus.subscribe(anyEvent(), ignored -> {
            throw new IllegalStateException("boom");
        });
        bus.subscribe(anyEvent(), envelopes::add);

        bus.publish(new TurnStartEvent("session-1", "turn-1", NOW));

        assertThat(envelopes).hasSize(1);
    }

    @Test
    void concurrentPublishUsesUniqueSequences() throws Exception {
        InMemoryEventBus bus = new InMemoryEventBus();
        Set<Long> sequences = ConcurrentHashMap.newKeySet();
        List<EventEnvelope> envelopes = Collections.synchronizedList(new ArrayList<>());
        bus.subscribe(anyEvent(), envelope -> {
            sequences.add(envelope.sequence());
            envelopes.add(envelope);
        });
        int eventCount = 32;
        CountDownLatch ready = new CountDownLatch(eventCount);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(eventCount)) {
            for (int index = 0; index < eventCount; index++) {
                int turnIndex = index;
                executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    bus.publish(new TurnStartEvent("session-1", "turn-" + turnIndex, NOW));
                });
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
        }

        assertThat(envelopes).hasSize(eventCount);
        assertThat(sequences).hasSize(eventCount);
        assertThat(sequences).contains(1L, 32L);
    }

    private static EventFilter anyEvent() {
        return new EventFilter(Optional.empty(), Optional.empty());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
