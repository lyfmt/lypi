package cn.lypi.runtime.subagent;

import cn.lypi.contracts.session.AgentLifecycleEntry;
import cn.lypi.contracts.subagent.HeadlessSubagentOutput;
import cn.lypi.contracts.subagent.MailboxMessage;
import cn.lypi.contracts.subagent.MailboxStatus;
import cn.lypi.contracts.subagent.SubagentResultRef;
import cn.lypi.contracts.subagent.SubagentRunStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

final class SubagentRunResultProjector {
    private final Clock clock;
    private final Supplier<String> idSupplier;

    SubagentRunResultProjector(Clock clock, Supplier<String> idSupplier) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.idSupplier = idSupplier;
    }

    static HeadlessSubagentOutput failedOutput(String childSessionId, Throwable failure) {
        return new HeadlessSubagentOutput(
            childSessionId,
            SubagentRunStatus.FAILED,
            "",
            Optional.empty(),
            Optional.ofNullable(failure == null ? "Subagent failed" : failure.getMessage())
        );
    }

    AgentLifecycleEntry lifecycleEntry(DefaultAgentCenter.RunningAgent running, HeadlessSubagentOutput output) {
        return new AgentLifecycleEntry(
            "entry_agent_" + idSupplier.get(),
            running.parentSpawnEntryId(),
            running.agentId(),
            running.childSessionId(),
            running.parentSessionId(),
            lifecycle(output.status()),
            Map.of(
                "status", output.status().name(),
                "errorMessage", output.errorMessage().orElse("")
            ),
            Instant.now(clock)
        );
    }

    MailboxMessage mailboxMessage(DefaultAgentCenter.RunningAgent running, HeadlessSubagentOutput output) {
        Instant now = Instant.now(clock);
        return new MailboxMessage(
            "mail_" + idSupplier.get(),
            running.agentId(),
            running.childSessionId(),
            running.parentSessionId(),
            running.parentSpawnEntryId(),
            mailboxSummary(output),
            new SubagentResultRef(
                running.childSessionId(),
                output.finalEntryId().orElse(""),
                Optional.empty(),
                Optional.of(output.status())
            ),
            MailboxStatus.PENDING,
            now,
            now
        );
    }

    private String mailboxSummary(HeadlessSubagentOutput output) {
        if (output.summary() != null && !output.summary().isBlank()) {
            return output.summary();
        }
        return output.errorMessage()
            .filter(message -> !message.isBlank())
            .orElse(output.status().name());
    }

    private String lifecycle(SubagentRunStatus status) {
        return switch (status) {
            case SUCCEEDED -> "finished";
            case INTERRUPTED -> "interrupted";
            case TIMED_OUT -> "timed_out";
            case STARTED, RUNNING, FAILED -> "failed";
        };
    }
}
