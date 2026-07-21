package cn.lypi.runtime.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.agent.SteeringMessage;
import cn.lypi.contracts.agent.SteeringMessageType;
import cn.lypi.contracts.subagent.MailboxMessage;
import cn.lypi.contracts.subagent.MailboxStatus;
import cn.lypi.contracts.subagent.SubagentRunStatus;
import cn.lypi.contracts.subagent.SubagentWaitResult;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultMailboxServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void waitAndAgentCommunicationAtomicallyConsumeOneCompletion() throws Exception {
        JsonlMailboxStore store = new JsonlMailboxStore(tempDir);
        DefaultMailboxService mailbox = new DefaultMailboxService(
            store,
            Clock.fixed(Instant.parse("2026-07-21T00:00:00Z"), ZoneOffset.UTC)
        );
        mailbox.publish(message());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<SubagentWaitResult> waited = executor.submit(() -> {
                ready.countDown();
                start.await();
                return mailbox.waitAndConsume("ses_parent", 1_000);
            });
            Future<Optional<SteeringMessage>> steered = executor.submit(() -> {
                ready.countDown();
                start.await();
                return mailbox.poll("ses_parent");
            });
            assertThat(ready.await(1, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            SubagentWaitResult waitResult = waited.get(2, TimeUnit.SECONDS);
            Optional<SteeringMessage> steering = steered.get(2, TimeUnit.SECONDS);

            assertThat(waitResult.received() ^ steering.isPresent()).isTrue();
            if (waitResult.received()) {
                assertThat(waitResult.runId()).contains("run_1");
            } else {
                assertThat(steering.orElseThrow().type()).isEqualTo(SteeringMessageType.AGENT_COMMUNICATION);
                assertThat(steering.orElseThrow().metadata()).containsEntry("runId", "run_1");
            }
        }

        assertThat(store.read("ses_parent", Set.of(MailboxStatus.DELIVERED)))
            .singleElement()
            .satisfies(delivered -> assertThat(delivered.runId()).isEqualTo("run_1"));
        assertThat(store.read("ses_parent", Set.of(MailboxStatus.PENDING))).isEmpty();
    }

    @Test
    void publishWakesWaitingConsumerAndReturnsFullIdentity() throws Exception {
        DefaultMailboxService mailbox = new DefaultMailboxService(
            new JsonlMailboxStore(tempDir),
            Clock.systemUTC()
        );

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<SubagentWaitResult> waited = executor.submit(() -> mailbox.waitAndConsume("ses_parent", 2_000));
            mailbox.publish(message());

            assertThat(waited.get(2, TimeUnit.SECONDS)).satisfies(result -> {
                assertThat(result.received()).isTrue();
                assertThat(result.taskName()).contains("inspect-session");
                assertThat(result.agentId()).contains("agent_1");
                assertThat(result.childSessionId()).contains("ses_child");
                assertThat(result.runId()).contains("run_1");
                assertThat(result.status()).contains(SubagentRunStatus.SUCCEEDED);
                assertThat(result.content()).contains("inspection complete");
            });
        }
    }

    private MailboxMessage message() {
        return new MailboxMessage(
            "mail_1",
            "inspect-session",
            "agent_1",
            "ses_child",
            "run_1",
            "ses_parent",
            "entry_spawn",
            SubagentRunStatus.SUCCEEDED,
            "inspection complete",
            Optional.of("entry_final"),
            Optional.empty(),
            MailboxStatus.PENDING,
            Instant.EPOCH,
            Instant.EPOCH
        );
    }
}
