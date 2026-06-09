package cn.lypi.runtime.event;

import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.EventConsumer;
import cn.lypi.contracts.event.EventEnvelope;
import cn.lypi.contracts.event.EventFilter;
import cn.lypi.contracts.event.EventSubscription;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内同步事件总线。
 *
 * NOTE: v1 不做历史 replay、异步队列或持久 event log，只负责标准事件广播。
 */
public final class InMemoryEventBus implements EventBus {
    private final AtomicLong sequence = new AtomicLong();
    private final CopyOnWriteArrayList<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    @Override
    public void publish(AgentEvent event) {
        AgentEvent safeEvent = Objects.requireNonNull(event, "event must not be null");
        long nextSequence = sequence.incrementAndGet();
        EventEnvelope envelope = new EventEnvelope(
            "evt_" + nextSequence,
            safeEvent.sessionId(),
            nextSequence,
            safeEvent
        );
        for (Subscriber subscriber : subscribers) {
            subscriber.accept(envelope);
        }
    }

    @Override
    public EventSubscription subscribe(EventFilter filter, EventConsumer consumer) {
        Subscriber subscriber = new Subscriber(
            normalizeFilter(filter),
            Objects.requireNonNull(consumer, "consumer must not be null"),
            subscribers
        );
        subscribers.add(subscriber);
        return subscriber;
    }

    int subscriberCount() {
        return subscribers.size();
    }

    private EventFilter normalizeFilter(EventFilter filter) {
        if (filter == null) {
            return new EventFilter(Optional.empty(), Optional.empty());
        }
        return new EventFilter(
            filter.sessionId() == null ? Optional.empty() : filter.sessionId(),
            filter.eventType() == null ? Optional.empty() : filter.eventType()
        );
    }

    private static final class Subscriber implements EventSubscription {
        private final EventFilter filter;
        private final EventConsumer consumer;
        private final CopyOnWriteArrayList<Subscriber> subscribers;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Subscriber(EventFilter filter, EventConsumer consumer, CopyOnWriteArrayList<Subscriber> subscribers) {
            this.filter = filter;
            this.consumer = consumer;
            this.subscribers = subscribers;
        }

        private void accept(EventEnvelope envelope) {
            if (closed.get() || !matches(envelope)) {
                return;
            }
            try {
                consumer.accept(envelope);
            } catch (RuntimeException exception) {
                // NOTE: 单个订阅者故障不得阻断发布方或其他订阅者。
            }
        }

        private boolean matches(EventEnvelope envelope) {
            return filter.sessionId()
                .map(sessionId -> sessionId.equals(envelope.sessionId()))
                .orElse(true)
                && filter.eventType()
                    .map(eventType -> eventType.isInstance(envelope.event()))
                    .orElse(true);
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                subscribers.remove(this);
            }
        }
    }
}
