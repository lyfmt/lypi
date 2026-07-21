package cn.lypi.runtime.subagent;

import cn.lypi.contracts.agent.SteeringMessage;
import cn.lypi.contracts.common.SignalSubscription;
import cn.lypi.contracts.runtime.AgentCommunicationPort;
import cn.lypi.contracts.subagent.MailboxMessage;
import cn.lypi.contracts.subagent.MailboxStatus;
import cn.lypi.contracts.subagent.SubagentWaitRequest;
import cn.lypi.contracts.subagent.SubagentWaitResult;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class DefaultMailboxService implements AgentCommunicationPort {
    private final JsonlMailboxStore store;
    private final Clock clock;

    public DefaultMailboxService(JsonlMailboxStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public synchronized void publish(MailboxMessage message) {
        store.append(message);
        notifyAll();
    }

    public synchronized List<MailboxMessage> read(String sessionId, Set<MailboxStatus> statuses) {
        return store.read(sessionId, statuses);
    }

    public SubagentWaitResult waitAndConsume(String parentSessionId, long timeoutMillis) {
        return waitAndConsume(new SubagentWaitRequest(parentSessionId, timeoutMillis));
    }

    public SubagentWaitResult waitAndConsume(SubagentWaitRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        try (
            SignalSubscription ignoredAbort = request.abortSignal().subscribe(this::signalWaiters);
            SignalSubscription ignoredSteering = request.steeringMessages().subscribe(this::signalWaiters)
        ) {
            return waitLoop(request);
        }
    }

    private synchronized SubagentWaitResult waitLoop(SubagentWaitRequest request) {
        long remainingNanos = TimeUnit.MILLISECONDS.toNanos(request.timeoutMillis());
        long deadline = System.nanoTime() + remainingNanos;
        while (true) {
            if (request.abortSignal().aborted()) {
                return SubagentWaitResult.aborted();
            }
            if (request.steeringMessages().hasPending()) {
                return SubagentWaitResult.steered();
            }
            Optional<MailboxMessage> message = consumePending(request.parentSessionId());
            if (message.isPresent()) {
                return waitResult(message.orElseThrow());
            }
            if (remainingNanos <= 0) {
                return SubagentWaitResult.timedOut();
            }
            try {
                long millis = remainingNanos / 1_000_000L;
                int nanos = (int) (remainingNanos % 1_000_000L);
                wait(millis, nanos);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return SubagentWaitResult.aborted();
            }
            remainingNanos = deadline - System.nanoTime();
        }
    }

    private synchronized void signalWaiters() {
        notifyAll();
    }

    @Override
    public synchronized Optional<SteeringMessage> poll(String parentSessionId) {
        return consumePending(parentSessionId).map(this::steeringMessage);
    }

    private Optional<MailboxMessage> consumePending(String parentSessionId) {
        Optional<MailboxMessage> pending = store.read(parentSessionId, Set.of(MailboxStatus.PENDING)).stream().findFirst();
        if (pending.isEmpty()) {
            return Optional.empty();
        }
        MailboxMessage delivered = withStatus(pending.orElseThrow(), MailboxStatus.DELIVERED);
        store.append(delivered);
        return Optional.of(delivered);
    }

    private MailboxMessage withStatus(MailboxMessage message, MailboxStatus status) {
        return new MailboxMessage(
            message.mailId(),
            message.taskName(),
            message.agentId(),
            message.childSessionId(),
            message.runId(),
            message.parentSessionId(),
            message.parentSpawnEntryId(),
            message.runStatus(),
            message.content(),
            message.finalEntryId(),
            message.errorMessage(),
            status,
            message.createdAt(),
            Instant.now(clock)
        );
    }

    private SubagentWaitResult waitResult(MailboxMessage message) {
        return SubagentWaitResult.completed(
            message.taskName(),
            message.agentId(),
            message.childSessionId(),
            message.runId(),
            message.runStatus(),
            message.content()
        );
    }

    private SteeringMessage steeringMessage(MailboxMessage message) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("taskName", message.taskName());
        metadata.put("agentId", message.agentId());
        metadata.put("childSessionId", message.childSessionId());
        metadata.put("runId", message.runId());
        metadata.put("status", message.runStatus().name());
        message.finalEntryId().ifPresent(value -> metadata.put("finalEntryId", value));
        message.errorMessage().ifPresent(value -> metadata.put("errorMessage", value));
        return SteeringMessage.agentCommunication(message.content(), Map.copyOf(metadata));
    }
}
