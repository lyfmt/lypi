package cn.lypi.runtime.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.session.AgentLifecycleEntry;
import cn.lypi.contracts.subagent.HeadlessSubagentOutput;
import cn.lypi.contracts.subagent.MailboxMessage;
import cn.lypi.contracts.subagent.MailboxStatus;
import cn.lypi.contracts.subagent.SubagentRunStatus;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SubagentRunResultProjectorTest {
    private static final Instant NOW = Instant.parse("2026-06-09T00:00:00Z");

    @Test
    void createsFailureOutputWhenProcessFailsWithoutStructuredOutput() {
        HeadlessSubagentOutput output = SubagentRunResultProjector.failedOutput(
            "ses_child",
            new IllegalStateException("boom")
        );

        assertThat(output.childSessionId()).isEqualTo("ses_child");
        assertThat(output.status()).isEqualTo(SubagentRunStatus.FAILED);
        assertThat(output.summary()).isEmpty();
        assertThat(output.errorMessage()).contains("boom");
    }

    @Test
    void createsLifecycleEntryForCompletion() {
        SubagentRunResultProjector projector = new SubagentRunResultProjector(
            Clock.fixed(NOW, ZoneOffset.UTC),
            () -> "fixed"
        );
        DefaultAgentCenter.RunningAgent running = new DefaultAgentCenter.RunningAgent(
            "agent_1",
            "ses_child",
            "ses_parent",
            "entry_spawn",
            Optional.empty(),
            Optional.empty(),
            Path.of("."),
            null
        );

        AgentLifecycleEntry entry = projector.lifecycleEntry(running, new HeadlessSubagentOutput(
            "ses_child",
            SubagentRunStatus.SUCCEEDED,
            "summary",
            Optional.of("entry_final"),
            Optional.empty()
        ));

        assertThat(entry.id()).isEqualTo("entry_agent_fixed");
        assertThat(entry.parentId()).isEqualTo("entry_spawn");
        assertThat(entry.lifecycle()).isEqualTo("finished");
        assertThat(entry.metadata()).containsEntry("status", "SUCCEEDED");
    }

    @Test
    void createsMailboxMessageWithSummaryFallback() {
        SubagentRunResultProjector projector = new SubagentRunResultProjector(
            Clock.fixed(NOW, ZoneOffset.UTC),
            () -> "fixed"
        );
        DefaultAgentCenter.RunningAgent running = new DefaultAgentCenter.RunningAgent(
            "agent_1",
            "ses_child",
            "ses_parent",
            "entry_spawn",
            Optional.empty(),
            Optional.empty(),
            Path.of("."),
            null
        );

        MailboxMessage message = projector.mailboxMessage(running, new HeadlessSubagentOutput(
            "ses_child",
            SubagentRunStatus.FAILED,
            "",
            Optional.empty(),
            Optional.of("failure summary")
        ));

        assertThat(message.mailId()).isEqualTo("mail_fixed");
        assertThat(message.summary()).isEqualTo("failure summary");
        assertThat(message.status()).isEqualTo(MailboxStatus.PENDING);
        assertThat(message.contentRef().status()).contains(SubagentRunStatus.FAILED);
    }
}
