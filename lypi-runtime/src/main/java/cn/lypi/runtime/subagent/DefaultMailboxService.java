package cn.lypi.runtime.subagent;

import cn.lypi.contracts.agent.SteeringMessage;
import cn.lypi.contracts.runtime.AgentCommunicationPort;
import cn.lypi.contracts.runtime.MailboxPort;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.subagent.MailboxCommandResult;
import cn.lypi.contracts.subagent.MailboxMessage;
import cn.lypi.contracts.subagent.MailboxStatus;
import cn.lypi.contracts.subagent.SubagentWaitResult;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class DefaultMailboxService implements MailboxPort, AgentCommunicationPort {
    private final JsonlMailboxStore store;
    private final SessionManagerPort sessionManager;
    private final Clock clock;

    public DefaultMailboxService(JsonlMailboxStore store, SessionManagerPort sessionManager, Clock clock) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public synchronized void publish(MailboxMessage message) {
        store.append(message);
        notifyAll();
    }

    @Override
    public synchronized List<MailboxMessage> read(String sessionId, Set<MailboxStatus> statuses) {
        return store.read(sessionId, statuses);
    }

    public synchronized SubagentWaitResult waitAndConsume(String parentSessionId, long timeoutMillis) {
        long remainingNanos = Math.max(0, timeoutMillis) * 1_000_000L;
        long deadline = System.nanoTime() + remainingNanos;
        while (true) {
            Optional<MailboxMessage> message = consumePending(parentSessionId);
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
                return SubagentWaitResult.timedOut();
            }
            remainingNanos = deadline - System.nanoTime();
        }
    }

    @Override
    public synchronized Optional<SteeringMessage> poll(String parentSessionId) {
        return consumePending(parentSessionId).map(this::steeringMessage);
    }

    @Override
    public synchronized MailboxCommandResult accept(String sessionId, String mailId) {
        Optional<MailboxMessage> message = store.read(sessionId, Set.of()).stream()
            .filter(candidate -> candidate.mailId().equals(mailId))
            .filter(candidate -> candidate.status() == MailboxStatus.PENDING)
            .findFirst();
        if (message.isEmpty()) {
            return MailboxCommandResult.failure("Mailbox message is not pending: " + mailId);
        }
        MailboxMessage delivered = withStatus(message.orElseThrow(), MailboxStatus.DELIVERED);
        store.append(delivered);
        return MailboxCommandResult.success(delivered);
    }

    @Override
    public MailboxCommandResult stash(String sessionId, String mailId) {
        return MailboxCommandResult.failure("Mailbox stash is not supported");
    }

    @Override
    public MailboxCommandResult discard(String sessionId, String mailId) {
        return MailboxCommandResult.failure("Mailbox discard is not supported");
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
