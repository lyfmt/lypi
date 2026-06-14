package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.runtime.MailboxPort;
import cn.lypi.contracts.subagent.MailboxCommandResult;
import cn.lypi.contracts.subagent.MailboxMessage;
import cn.lypi.contracts.subagent.MailboxStatus;
import cn.lypi.contracts.subagent.SubagentResultRef;
import cn.lypi.contracts.tui.SlashCommand;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MailboxSlashCommandHandlerTest {
    @Test
    void commandMetadataExposesMailboxSlashCommand() {
        MailboxSlashCommandHandler handler = new MailboxSlashCommandHandler(new RecordingMailbox(), () -> "ses_parent");

        SlashCommand command = handler.command();

        assertEquals("mailbox", command.name());
        assertTrue(command.description().contains("mailbox"));
        assertTrue(command.parameters().stream().anyMatch(parameter -> "action".equals(parameter.name())));
        assertTrue(command.parameters().stream().anyMatch(parameter -> "mailId".equals(parameter.name())));
        assertEquals(handler, command.handler());
    }

    @Test
    void listReadsPendingMailboxForCurrentSession() {
        RecordingMailbox mailbox = new RecordingMailbox();
        MailboxSlashCommandHandler handler = new MailboxSlashCommandHandler(mailbox, () -> "ses_parent");

        handler.handle(Map.of("action", "list"));

        assertEquals("ses_parent", mailbox.readSessionId);
        assertEquals(Set.of(MailboxStatus.PENDING), mailbox.readStatuses);
        assertTrue(handler.lastOutput().contains("mail_1"));
        assertTrue(handler.lastOutput().contains("子任务完成"));
    }

    @Test
    void acceptStashAndDiscardMailboxMessageForCurrentSession() {
        RecordingMailbox mailbox = new RecordingMailbox();
        MailboxSlashCommandHandler handler = new MailboxSlashCommandHandler(mailbox, () -> "ses_parent");

        handler.handle(Map.of("action", "accept", "mailId", "mail_1"));
        assertEquals("accept:ses_parent:mail_1", mailbox.lastCommand);
        assertTrue(handler.lastOutput().contains("已接收"));

        handler.handle(Map.of("action", "stash", "mailId", "mail_1"));
        assertEquals("stash:ses_parent:mail_1", mailbox.lastCommand);
        assertTrue(handler.lastOutput().contains("已暂存"));

        handler.handle(Map.of("action", "discard", "mailId", "mail_1"));
        assertEquals("discard:ses_parent:mail_1", mailbox.lastCommand);
        assertTrue(handler.lastOutput().contains("已丢弃"));
    }

    @Test
    void defaultsToAcceptWhenMailIdIsProvidedWithoutAction() {
        RecordingMailbox mailbox = new RecordingMailbox();
        MailboxSlashCommandHandler handler = new MailboxSlashCommandHandler(mailbox, () -> "ses_parent");

        handler.handle(Map.of("mailId", "mail_1"));

        assertEquals("accept:ses_parent:mail_1", mailbox.lastCommand);
    }

    @Test
    void missingMailIdForCommandActionReturnsUserFacingError() {
        MailboxSlashCommandHandler handler = new MailboxSlashCommandHandler(new RecordingMailbox(), () -> "ses_parent");

        handler.handle(Map.of("action", "accept"));

        assertTrue(handler.lastOutput().contains("mailId 不能为空"));
    }

    @Test
    void invalidStatusFilterReturnsUserFacingError() {
        RecordingMailbox mailbox = new RecordingMailbox();
        MailboxSlashCommandHandler handler = new MailboxSlashCommandHandler(mailbox, () -> "ses_parent");

        handler.handle(Map.of("action", "list", "statuses", "missing"));

        assertTrue(handler.lastOutput().contains("未知 mailbox status"));
        assertEquals(null, mailbox.readSessionId);
    }

    private static MailboxMessage message(MailboxStatus status) {
        return new MailboxMessage(
            "mail_1",
            "agent_1",
            "ses_child",
            "ses_parent",
            "entry_spawn",
            "子任务完成",
            new SubagentResultRef("ses_child", "entry_final", Optional.empty()),
            status,
            Instant.EPOCH,
            Instant.EPOCH
        );
    }

    private static final class RecordingMailbox implements MailboxPort {
        private String readSessionId;
        private Set<MailboxStatus> readStatuses;
        private String lastCommand;

        @Override
        public List<MailboxMessage> read(String sessionId, Set<MailboxStatus> statuses) {
            this.readSessionId = sessionId;
            this.readStatuses = statuses;
            return List.of(message(MailboxStatus.PENDING));
        }

        @Override
        public MailboxCommandResult accept(String sessionId, String mailId) {
            lastCommand = "accept:" + sessionId + ":" + mailId;
            return MailboxCommandResult.success(message(MailboxStatus.DELIVERED));
        }

        @Override
        public MailboxCommandResult stash(String sessionId, String mailId) {
            lastCommand = "stash:" + sessionId + ":" + mailId;
            return MailboxCommandResult.success(message(MailboxStatus.STASHED));
        }

        @Override
        public MailboxCommandResult discard(String sessionId, String mailId) {
            lastCommand = "discard:" + sessionId + ":" + mailId;
            return MailboxCommandResult.success(message(MailboxStatus.DISCARDED));
        }
    }
}
