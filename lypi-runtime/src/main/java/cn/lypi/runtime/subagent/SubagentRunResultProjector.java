package cn.lypi.runtime.subagent;

import cn.lypi.contracts.subagent.HeadlessSubagentOutput;
import cn.lypi.contracts.subagent.MailboxMessage;
import cn.lypi.contracts.subagent.MailboxStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

final class SubagentRunResultProjector {
    private final Clock clock;
    private final Supplier<String> idSupplier;

    SubagentRunResultProjector(Clock clock, Supplier<String> idSupplier) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.idSupplier = idSupplier;
    }

    MailboxMessage mailboxMessage(RunningSubagentRun running, HeadlessSubagentOutput output) {
        Instant now = Instant.now(clock);
        return new MailboxMessage(
            "mail_" + idSupplier.get(),
            running.agent().taskName(),
            running.agent().agentId(),
            running.agent().childSessionId(),
            running.runId(),
            running.agent().parentSessionId(),
            running.agent().parentSpawnEntryId(),
            output.status(),
            completionContent(output),
            output.finalEntryId(),
            output.errorMessage(),
            MailboxStatus.PENDING,
            now,
            now
        );
    }

    private String completionContent(HeadlessSubagentOutput output) {
        if (!output.content().isBlank()) {
            return output.content();
        }
        return output.errorMessage().filter(message -> !message.isBlank()).orElse(output.status().name());
    }

}
