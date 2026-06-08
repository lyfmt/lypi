package cn.lypi.runtime.subagent;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.runtime.MailboxPort;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.subagent.MailboxCommandResult;
import cn.lypi.contracts.subagent.MailboxMessage;
import cn.lypi.contracts.subagent.MailboxStatus;
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
    public void publish(MailboxMessage message) {
        store.append(message);
    }

    @Override
    public List<MailboxMessage> read(String sessionId, Set<MailboxStatus> statuses) {
        return store.read(sessionId, statuses);
    }

    @Override
    public MailboxCommandResult accept(String sessionId, String mailId) {
        Optional<MailboxMessage> message = latest(sessionId, mailId);
        if (message.isEmpty()) {
            return MailboxCommandResult.failure("Mailbox message not found: " + mailId);
        }
        MailboxMessage delivered = withStatus(message.get(), MailboxStatus.DELIVERED);
        sessionManager.appendMessage(message(delivered));
        store.append(delivered);
        return MailboxCommandResult.success(delivered);
    }

    @Override
    public MailboxCommandResult stash(String sessionId, String mailId) {
        return updateStatus(sessionId, mailId, MailboxStatus.STASHED);
    }

    @Override
    public MailboxCommandResult discard(String sessionId, String mailId) {
        return updateStatus(sessionId, mailId, MailboxStatus.DISCARDED);
    }

    private MailboxCommandResult updateStatus(String sessionId, String mailId, MailboxStatus status) {
        Optional<MailboxMessage> message = latest(sessionId, mailId);
        if (message.isEmpty()) {
            return MailboxCommandResult.failure("Mailbox message not found: " + mailId);
        }
        MailboxMessage updated = withStatus(message.get(), status);
        store.append(updated);
        return MailboxCommandResult.success(updated);
    }

    private Optional<MailboxMessage> latest(String sessionId, String mailId) {
        return store.read(sessionId, Set.of()).stream()
            .filter(message -> message.mailId().equals(mailId))
            .findFirst();
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
