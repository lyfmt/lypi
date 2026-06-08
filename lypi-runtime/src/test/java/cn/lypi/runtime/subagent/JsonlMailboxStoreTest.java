package cn.lypi.runtime.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lypi.contracts.subagent.MailboxMessage;
import cn.lypi.contracts.subagent.MailboxStatus;
import cn.lypi.contracts.subagent.SubagentResultRef;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonlMailboxStoreTest {
    private static final Instant NOW = Instant.parse("2026-06-09T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void appendOnlyStoreProjectsLatestMailboxStatus() throws Exception {
        JsonlMailboxStore store = new JsonlMailboxStore(tempDir);
        MailboxMessage pending = message(MailboxStatus.PENDING, NOW);
        MailboxMessage stashed = new MailboxMessage(
            pending.mailId(),
            pending.agentId(),
            pending.childSessionId(),
            pending.parentSessionId(),
            pending.parentSpawnEntryId(),
            pending.summary(),
            pending.contentRef(),
            MailboxStatus.STASHED,
            pending.createdAt(),
            NOW.plusSeconds(1)
        );

        store.append(pending);
        store.append(stashed);

        assertThat(store.read("ses_parent", Set.of(MailboxStatus.STASHED)))
            .containsExactly(stashed);
        assertThat(store.read("ses_parent", Set.of(MailboxStatus.PENDING))).isEmpty();
        assertThat(Files.readAllLines(tempDir.resolve(".lypi").resolve("mailbox").resolve("ses_parent.jsonl")))
            .hasSize(2);
    }

    @Test
    void rejectsMailboxSessionIdsThatEscapeMailboxDirectory() {
        JsonlMailboxStore store = new JsonlMailboxStore(tempDir);
        MailboxMessage traversal = new MailboxMessage(
            "mail_01",
            "agent_01",
            "ses_child",
            "../sessions/ses_parent",
            "entry_spawn",
            "完成摘要",
            new SubagentResultRef("ses_child", "entry_final", Optional.empty()),
            MailboxStatus.PENDING,
            NOW,
            NOW
        );

        assertThatThrownBy(() -> store.append(traversal))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid mailbox session id");
        assertThatThrownBy(() -> store.read("../sessions/ses_parent", Set.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid mailbox session id");
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
}
