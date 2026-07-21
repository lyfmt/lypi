package cn.lypi.runtime.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lypi.contracts.subagent.MailboxMessage;
import cn.lypi.contracts.subagent.MailboxStatus;
import cn.lypi.contracts.subagent.SubagentRunStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class JsonlMailboxStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void readsLatestDeliveryProjectionWithRunIdentity() {
        JsonlMailboxStore store = new JsonlMailboxStore(tempDir);
        store.append(message(MailboxStatus.PENDING));
        store.append(message(MailboxStatus.DELIVERED));

        assertThat(store.read("ses_parent", Set.of(MailboxStatus.DELIVERED)))
            .singleElement()
            .satisfies(message -> {
                assertThat(message.runId()).isEqualTo("run_1");
                assertThat(message.status()).isEqualTo(MailboxStatus.DELIVERED);
            });
    }

    @Test
    void rejectsTraversalSessionId() {
        JsonlMailboxStore store = new JsonlMailboxStore(tempDir);
        assertThatThrownBy(() -> store.append(new MailboxMessage(
            "mail_1", "task", "agent_1", "ses_child", "run_1", "../outside", "entry_spawn",
            SubagentRunStatus.SUCCEEDED, "done", Optional.empty(), Optional.empty(), MailboxStatus.PENDING,
            Instant.EPOCH, Instant.EPOCH
        ))).isInstanceOf(IllegalArgumentException.class);
    }

    private MailboxMessage message(MailboxStatus status) {
        return new MailboxMessage(
            "mail_1",
            "inspect-session",
            "agent_1",
            "ses_child",
            "run_1",
            "ses_parent",
            "entry_spawn",
            SubagentRunStatus.SUCCEEDED,
            "done",
            Optional.of("entry_final"),
            Optional.empty(),
            status,
            Instant.EPOCH,
            Instant.EPOCH
        );
    }
}
