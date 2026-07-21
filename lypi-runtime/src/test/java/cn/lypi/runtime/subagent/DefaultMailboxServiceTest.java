package cn.lypi.runtime.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.agent.SteeringMessage;
import cn.lypi.contracts.agent.SteeringMessageSource;
import cn.lypi.contracts.agent.SteeringMessageType;
import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.common.SignalSubscription;
import cn.lypi.contracts.subagent.MailboxMessage;
import cn.lypi.contracts.subagent.MailboxStatus;
import cn.lypi.contracts.subagent.SubagentRunStatus;
import cn.lypi.contracts.subagent.SubagentWaitOutcome;
import cn.lypi.contracts.subagent.SubagentWaitRequest;
import cn.lypi.contracts.subagent.SubagentWaitResult;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    @Test
    void existingSteeringReturnsImmediatelyWithoutConsumingIt() {
        DefaultMailboxService mailbox = mailbox();
        TestSteeringSource steering = new TestSteeringSource();
        SteeringMessage message = SteeringMessage.user("stop waiting", java.util.List.of());
        steering.add(message);

        SubagentWaitResult result = mailbox.waitAndConsume(request(1_000, AbortSignal.none(), steering));

        assertThat(result.outcome()).isEqualTo(SubagentWaitOutcome.STEERED);
        assertThat(steering.poll()).containsSame(message);
    }

    @Test
    void newSteeringWakesWaitWithoutConsumingIt() throws Exception {
        DefaultMailboxService mailbox = mailbox();
        TestSteeringSource steering = new TestSteeringSource();

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<SubagentWaitResult> waited = executor.submit(() ->
                mailbox.waitAndConsume(request(10_000, AbortSignal.none(), steering))
            );
            assertThat(steering.subscribed.await(1, TimeUnit.SECONDS)).isTrue();
            SteeringMessage message = SteeringMessage.user("stop waiting", java.util.List.of());
            steering.add(message);

            assertThat(waited.get(1, TimeUnit.SECONDS).outcome()).isEqualTo(SubagentWaitOutcome.STEERED);
            assertThat(steering.poll()).containsSame(message);
            assertThat(steering.listeners).isEmpty();
        }
    }

    @Test
    void abortWakesWait() throws Exception {
        DefaultMailboxService mailbox = mailbox();
        TestAbortSignal abort = new TestAbortSignal();

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<SubagentWaitResult> waited = executor.submit(() ->
                mailbox.waitAndConsume(request(10_000, abort, SteeringMessageSource.none()))
            );
            assertThat(abort.subscribed.await(1, TimeUnit.SECONDS)).isTrue();
            abort.abort();

            assertThat(waited.get(1, TimeUnit.SECONDS).outcome()).isEqualTo(SubagentWaitOutcome.ABORTED);
            assertThat(abort.listeners).isEmpty();
        }
    }

    @Test
    void steeringWinsOverPendingCompletionWithoutConsumingMailbox() {
        DefaultMailboxService mailbox = mailbox();
        TestSteeringSource steering = new TestSteeringSource();
        mailbox.publish(message());
        steering.add(SteeringMessage.user("change course", java.util.List.of()));

        SubagentWaitResult result = mailbox.waitAndConsume(request(1_000, AbortSignal.none(), steering));

        assertThat(result.outcome()).isEqualTo(SubagentWaitOutcome.STEERED);
        assertThat(mailbox.read("ses_parent", Set.of(MailboxStatus.PENDING))).hasSize(1);
        assertThat(mailbox.read("ses_parent", Set.of(MailboxStatus.DELIVERED))).isEmpty();
    }

    @Test
    void abortWinsOverSteeringAndPendingCompletion() {
        DefaultMailboxService mailbox = mailbox();
        TestAbortSignal abort = new TestAbortSignal();
        TestSteeringSource steering = new TestSteeringSource();
        mailbox.publish(message());
        steering.add(SteeringMessage.user("change course", java.util.List.of()));
        abort.abort();

        SubagentWaitResult result = mailbox.waitAndConsume(request(1_000, abort, steering));

        assertThat(result.outcome()).isEqualTo(SubagentWaitOutcome.ABORTED);
        assertThat(steering.hasPending()).isTrue();
        assertThat(mailbox.read("ses_parent", Set.of(MailboxStatus.PENDING))).hasSize(1);
    }

    @Test
    void noActivityReturnsTimedOut() {
        SubagentWaitResult result = mailbox().waitAndConsume(request(
            5,
            AbortSignal.none(),
            SteeringMessageSource.none()
        ));

        assertThat(result.outcome()).isEqualTo(SubagentWaitOutcome.TIMED_OUT);
    }

    @Test
    void threadInterruptionReturnsAbortedAndRestoresInterruptFlag() throws Exception {
        DefaultMailboxService mailbox = mailbox();
        AtomicBoolean interrupted = new AtomicBoolean();

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<SubagentWaitResult> waited = executor.submit(() -> {
                Thread.currentThread().interrupt();
                SubagentWaitResult result = mailbox.waitAndConsume(request(
                    10_000,
                    AbortSignal.none(),
                    SteeringMessageSource.none()
                ));
                interrupted.set(Thread.currentThread().isInterrupted());
                return result;
            });

            assertThat(waited.get(1, TimeUnit.SECONDS).outcome()).isEqualTo(SubagentWaitOutcome.ABORTED);
            assertThat(interrupted).isTrue();
        }
    }

    private DefaultMailboxService mailbox() {
        return new DefaultMailboxService(new JsonlMailboxStore(tempDir), Clock.systemUTC());
    }

    private SubagentWaitRequest request(
        long timeoutMillis,
        AbortSignal abortSignal,
        SteeringMessageSource steeringMessages
    ) {
        return new SubagentWaitRequest("ses_parent", timeoutMillis, abortSignal, steeringMessages);
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

    private static final class TestAbortSignal implements AbortSignal {
        private final AtomicBoolean aborted = new AtomicBoolean();
        private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
        private final CountDownLatch subscribed = new CountDownLatch(1);

        @Override
        public boolean aborted() {
            return aborted.get();
        }

        @Override
        public SignalSubscription subscribe(Runnable listener) {
            listeners.add(listener);
            subscribed.countDown();
            if (aborted()) {
                listener.run();
            }
            return () -> listeners.remove(listener);
        }

        private void abort() {
            if (aborted.compareAndSet(false, true)) {
                listeners.forEach(Runnable::run);
            }
        }
    }

    private static final class TestSteeringSource implements SteeringMessageSource {
        private final ConcurrentLinkedQueue<SteeringMessage> messages = new ConcurrentLinkedQueue<>();
        private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
        private final CountDownLatch subscribed = new CountDownLatch(1);

        @Override
        public Optional<SteeringMessage> poll() {
            return Optional.ofNullable(messages.poll());
        }

        @Override
        public boolean hasPending() {
            return !messages.isEmpty();
        }

        @Override
        public SignalSubscription subscribe(Runnable listener) {
            listeners.add(listener);
            subscribed.countDown();
            if (hasPending()) {
                listener.run();
            }
            return () -> listeners.remove(listener);
        }

        private void add(SteeringMessage message) {
            messages.add(message);
            listeners.forEach(Runnable::run);
        }
    }
}
