package cn.lypi.runtime.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.subagent.HeadlessSubagentOutput;
import cn.lypi.contracts.subagent.MailboxStatus;
import cn.lypi.contracts.subagent.SubagentRunStatus;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SubagentRunResultProjectorTest {
    @Test
    void projectsAuthoritativeAgentAndRunIdentity() {
        SubagentAgent agent = new SubagentAgent(
            "agent_1",
            "inspect-session",
            "ses_child",
            "ses_parent",
            "entry_spawn",
            Path.of("/workspace")
        );
        RunningSubagentRun run = new RunningSubagentRun("run_1", agent, null);
        HeadlessSubagentOutput output = new HeadlessSubagentOutput(
            "untrusted-task",
            "untrusted-agent",
            "untrusted-session",
            "untrusted-run",
            SubagentRunStatus.SUCCEEDED,
            "done",
            Optional.of("entry_final"),
            Optional.empty()
        );
        SubagentRunResultProjector projector = new SubagentRunResultProjector(
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
            () -> "1"
        );

        var mailbox = projector.mailboxMessage(run, output);

        assertThat(mailbox.taskName()).isEqualTo("inspect-session");
        assertThat(mailbox.agentId()).isEqualTo("agent_1");
        assertThat(mailbox.childSessionId()).isEqualTo("ses_child");
        assertThat(mailbox.runId()).isEqualTo("run_1");
        assertThat(mailbox.parentSpawnEntryId()).isEqualTo("entry_spawn");
        assertThat(mailbox.status()).isEqualTo(MailboxStatus.PENDING);
    }
}
