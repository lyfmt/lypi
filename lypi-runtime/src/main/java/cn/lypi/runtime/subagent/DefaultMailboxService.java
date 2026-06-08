package cn.lypi.runtime.subagent;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.runtime.MailboxPort;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.subagent.HeadlessSubagentOutput;
import cn.lypi.contracts.subagent.MailboxCommandResult;
import cn.lypi.contracts.subagent.MailboxMessage;
import cn.lypi.contracts.subagent.MailboxStatus;
import cn.lypi.contracts.subagent.SubagentRunStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class DefaultMailboxService implements MailboxPort {
    private final JsonlMailboxStore store;
    private final SessionManagerPort sessionManager;
    private final Clock clock;

    public DefaultMailboxService(JsonlMailboxStore store, SessionManagerPort sessionManager, Clock clock) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager must not be null");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * 投递一条新的 mailbox 消息。
     */
    public synchronized void publish(MailboxMessage message) {
        store.append(message);
    }

    @Override
    public List<MailboxMessage> read(String sessionId, Set<MailboxStatus> statuses) {
        return store.read(sessionId, statuses);
    }

    /**
     * 从持久化 mailbox content ref 恢复 subagent 输出摘要。
     */
    public Optional<HeadlessSubagentOutput> readResult(String childSessionId) {
        return store.findByChildSessionId(childSessionId)
            .map(message -> new HeadlessSubagentOutput(
                message.childSessionId(),
                recoveredStatus(message),
                message.summary(),
                blank(message.contentRef().finalEntryId()) ? Optional.empty() : Optional.of(message.contentRef().finalEntryId()),
                Optional.empty()
            ));
    }

    @Override
    public synchronized MailboxCommandResult accept(String sessionId, String mailId) {
        MailboxCommandResult sessionCheck = ensureCurrentSession(sessionId);
        if (!sessionCheck.success()) {
            return sessionCheck;
        }
        Optional<MailboxMessage> message = latest(sessionId, mailId);
        if (message.isEmpty()) {
            return MailboxCommandResult.failure("Mailbox message not found: " + mailId);
        }
        if (message.get().status() != MailboxStatus.PENDING && message.get().status() != MailboxStatus.STASHED) {
            return MailboxCommandResult.failure("Mailbox message cannot be accepted from status: " + message.get().status());
        }
        MailboxMessage delivered = withStatus(message.get(), MailboxStatus.DELIVERED);
        sessionManager.appendMessage(message(delivered));
        store.append(delivered);
        return MailboxCommandResult.success(delivered);
    }

    @Override
    public synchronized MailboxCommandResult stash(String sessionId, String mailId) {
        return updateStatus(sessionId, mailId, MailboxStatus.STASHED);
    }

    @Override
    public synchronized MailboxCommandResult discard(String sessionId, String mailId) {
        return updateStatus(sessionId, mailId, MailboxStatus.DISCARDED);
    }

    private MailboxCommandResult updateStatus(String sessionId, String mailId, MailboxStatus status) {
        MailboxCommandResult sessionCheck = ensureCurrentSession(sessionId);
        if (!sessionCheck.success()) {
            return sessionCheck;
        }
        Optional<MailboxMessage> message = latest(sessionId, mailId);
        if (message.isEmpty()) {
            return MailboxCommandResult.failure("Mailbox message not found: " + mailId);
        }
        MailboxStatus current = message.get().status();
        if (!canTransition(current, status)) {
            return MailboxCommandResult.failure("Mailbox message cannot transition from " + current + " to " + status);
        }
        MailboxMessage updated = withStatus(message.get(), status);
        store.append(updated);
        return MailboxCommandResult.success(updated);
    }

    private MailboxCommandResult ensureCurrentSession(String sessionId) {
        if (!sessionManager.currentView().sessionId().equals(sessionId)) {
            return MailboxCommandResult.failure("Current session does not match mailbox session: " + sessionId);
        }
        return MailboxCommandResult.success(null);
    }

    private boolean canTransition(MailboxStatus current, MailboxStatus next) {
        if (current == MailboxStatus.DELIVERED || current == MailboxStatus.DISCARDED) {
            return false;
        }
        if (next == MailboxStatus.STASHED) {
            return current == MailboxStatus.PENDING;
        }
        if (next == MailboxStatus.DISCARDED) {
            return current == MailboxStatus.PENDING || current == MailboxStatus.STASHED;
        }
        return false;
    }

    private Optional<MailboxMessage> latest(String sessionId, String mailId) {
        return store.read(sessionId, Set.of()).stream()
            .filter(message -> message.mailId().equals(mailId))
            .findFirst();
    }

    private SubagentRunStatus recoveredStatus(MailboxMessage message) {
        return blank(message.contentRef().finalEntryId()) ? SubagentRunStatus.FAILED : SubagentRunStatus.SUCCEEDED;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private MailboxMessage withStatus(MailboxMessage message, MailboxStatus status) {
        return new MailboxMessage(
            message.mailId(),
            message.agentId(),
            message.childSessionId(),
            message.parentSessionId(),
            message.parentSpawnEntryId(),
            message.summary(),
            message.contentRef(),
            status,
            message.createdAt(),
            Instant.now(clock)
        );
    }

    private AgentMessage message(MailboxMessage mailboxMessage) {
        String text = "以下是之前 subagent %s 对“%s”返回的消息：%n%n%s".formatted(
            mailboxMessage.agentId(),
            mailboxMessage.summary(),
            mailboxMessage.summary()
        );
        return new AgentMessage(
            "msg_mail_" + mailboxMessage.mailId(),
            MessageRole.ASSISTANT,
            MessageKind.TEXT,
            List.<ContentBlock>of(new TextContentBlock(text, Map.of())),
            Instant.now(clock),
            Optional.empty(),
            Optional.empty()
        );
    }
}
