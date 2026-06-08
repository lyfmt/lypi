package cn.lypi.runtime.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.session.ForkRequest;
import cn.lypi.contracts.session.SessionContext;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.session.SessionView;
import cn.lypi.contracts.subagent.MailboxMessage;
import cn.lypi.contracts.subagent.MailboxStatus;
import cn.lypi.contracts.subagent.SubagentResultRef;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MailboxDeliveryServiceTest {
    private static final Instant NOW = Instant.parse("2026-06-09T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void stashDoesNotAppendTranscriptAndLaterAcceptAppendsAtCurrentLeaf() {
        CapturingSessionManager session = new CapturingSessionManager("ses_parent", "entry_current");
        DefaultMailboxService mailbox = new DefaultMailboxService(
            new JsonlMailboxStore(tempDir),
            session,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        MailboxMessage pending = message(MailboxStatus.PENDING, NOW);
        mailbox.publish(pending);

        mailbox.stash("ses_parent", "mail_01");
        assertThat(session.messages).isEmpty();

        mailbox.accept("ses_parent", "mail_01");

        assertThat(session.messages).singleElement().satisfies(message -> {
            assertThat(message.content().getFirst().toString())
                .contains("以下是之前 subagent agent_01 对“完成摘要”返回的消息：")
                .contains("完成摘要");
        });
        assertThat(mailbox.read("ses_parent", Set.of(MailboxStatus.DELIVERED)))
            .singleElement()
            .extracting(MailboxMessage::mailId)
            .isEqualTo("mail_01");
    }

    @Test
    void deliveryGuardKeepsBusySessionPendingAndAllowsIdleAutoAccept() {
        CapturingSessionManager session = new CapturingSessionManager("ses_parent", "entry_current");
        DefaultMailboxService mailbox = new DefaultMailboxService(
            new JsonlMailboxStore(tempDir),
            session,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        MailboxMessage pending = message(MailboxStatus.PENDING, NOW);
        mailbox.publish(pending);
        MailboxDeliveryService busyDelivery = new MailboxDeliveryService(mailbox, ignored -> false);

        busyDelivery.tryDeliver(pending);

        assertThat(mailbox.read("ses_parent", Set.of(MailboxStatus.PENDING))).hasSize(1);
        assertThat(session.messages).isEmpty();

        MailboxDeliveryService idleDelivery = new MailboxDeliveryService(mailbox, ignored -> true);
        idleDelivery.tryDeliver(pending);

        assertThat(mailbox.read("ses_parent", Set.of(MailboxStatus.DELIVERED))).hasSize(1);
        assertThat(session.messages).hasSize(1);
    }

    private MailboxMessage message(MailboxStatus status, Instant now) {
        return new MailboxMessage(
            "mail_01",
            "agent_01",
            "ses_child",
            "ses_parent",
            "entry_spawn",
            "完成摘要",
            new SubagentResultRef("ses_child", "entry_final", Optional.empty()),
            status,
            now,
            now
        );
    }

    private static final class CapturingSessionManager implements SessionManagerPort {
        private final String sessionId;
        private String leafId;
        private final List<AgentMessage> messages = new ArrayList<>();

        private CapturingSessionManager(String sessionId, String leafId) {
            this.sessionId = sessionId;
            this.leafId = leafId;
        }

        @Override
        public SessionHandle openOrCreate(String sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SessionHandle append(SessionEntry entry) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SessionHandle switchLeaf(String leafId) {
            this.leafId = leafId;
            return new SessionHandle(sessionId, null, leafId, Map.of());
        }

        @Override
        public List<SessionEntry> branch(String leafId) {
            return List.of();
        }

        @Override
        public SessionView currentView() {
            return new SessionView(sessionId, leafId);
        }

        @Override
        public SessionView view(String leafId) {
            return new SessionView(sessionId, leafId);
        }

        @Override
        public List<AgentMessage> transcript(String leafId) {
            return messages;
        }

        @Override
        public SessionContext context(String leafId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SessionHandle appendMessage(AgentMessage message) {
            messages.add(message);
            leafId = "entry_message_" + messages.size();
            return new SessionHandle(sessionId, null, leafId, Map.of());
        }

        @Override
        public SessionHandle fork(ForkRequest request) {
            throw new UnsupportedOperationException();
        }
    }
}
