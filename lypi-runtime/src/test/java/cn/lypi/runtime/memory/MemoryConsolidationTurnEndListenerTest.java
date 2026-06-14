package cn.lypi.runtime.memory;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.event.EventFilter;
import cn.lypi.contracts.event.TurnEndEvent;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.session.BranchSummaryPlan;
import cn.lypi.contracts.session.ForkRequest;
import cn.lypi.contracts.session.SessionContext;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.session.SessionView;
import cn.lypi.runtime.event.InMemoryEventBus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

class MemoryConsolidationTurnEndListenerTest {
    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    @Test
    void submitsBackgroundRunnerWithSynchronouslyCapturedForkPoint() {
        InMemoryEventBus eventBus = new InMemoryEventBus();
        MutableSessionManager sessionManager = new MutableSessionManager("ses_main", "leaf-1");
        RecordingRunner runner = new RecordingRunner();
        RecordingAuditSink auditSink = new RecordingAuditSink();
        Executor inlineExecutor = command -> {
            sessionManager.leafId = "leaf-2";
            command.run();
        };
        new MemoryConsolidationTurnEndListener(
            eventBus,
            sessionManager,
            new MemoryConsolidationTrigger(1_200_000L, 30),
            runner,
            inlineExecutor,
            auditSink
        ).start();

        eventBus.publish(completedEvent(1_200_000L, 0));

        assertThat(runner.requests)
            .containsExactly(new MemoryConsolidationRequest("ses_main", "leaf-1"));
        assertThat(auditSink.stages())
            .containsExactly(MemoryConsolidationAuditStage.ELIGIBLE, MemoryConsolidationAuditStage.SUBMITTED);
        assertThat(auditSink.record(MemoryConsolidationAuditStage.SUBMITTED).forkPointEntryId()).isEqualTo("leaf-1");
    }

    @Test
    void skipsFailedTurnEvenWhenThresholdsAreMet() {
        InMemoryEventBus eventBus = new InMemoryEventBus();
        RecordingRunner runner = new RecordingRunner();
        new MemoryConsolidationTurnEndListener(
            eventBus,
            new MutableSessionManager("ses_main", "leaf-1"),
            new MemoryConsolidationTrigger(1_200_000L, 30),
            runner,
            Runnable::run
        ).start();

        eventBus.publish(new TurnEndEvent(
            "ses_main",
            "turn_1",
            "FAILED",
            NOW,
            NOW.plusMillis(1_500_000L),
            1_500_000L,
            31,
            NOW.plusMillis(1_500_000L)
        ));

        assertThat(runner.requests).isEmpty();
    }

    @Test
    void auditsThresholdSkip() {
        InMemoryEventBus eventBus = new InMemoryEventBus();
        RecordingAuditSink auditSink = new RecordingAuditSink();
        new MemoryConsolidationTurnEndListener(
            eventBus,
            new MutableSessionManager("ses_main", "leaf-1"),
            new MemoryConsolidationTrigger(1_200_000L, 30),
            new RecordingRunner(),
            Runnable::run,
            auditSink
        ).start();

        eventBus.publish(completedEvent(1_000L, 30));

        assertThat(auditSink.stages()).containsExactly(MemoryConsolidationAuditStage.SKIPPED_THRESHOLD);
    }

    @Test
    void skipsTurnFromAnotherSession() {
        InMemoryEventBus eventBus = new InMemoryEventBus();
        RecordingRunner runner = new RecordingRunner();
        new MemoryConsolidationTurnEndListener(
            eventBus,
            new MutableSessionManager("ses_main", "leaf-1"),
            new MemoryConsolidationTrigger(1_200_000L, 30),
            runner,
            Runnable::run
        ).start();

        eventBus.publish(new TurnEndEvent(
            "ses_other",
            "turn_1",
            "COMPLETED",
            NOW,
            NOW.plusMillis(1_500_000L),
            1_500_000L,
            31,
            NOW.plusMillis(1_500_000L)
        ));

        assertThat(runner.requests).isEmpty();
    }

    @Test
    void auditsSessionMismatch() {
        InMemoryEventBus eventBus = new InMemoryEventBus();
        RecordingAuditSink auditSink = new RecordingAuditSink();
        new MemoryConsolidationTurnEndListener(
            eventBus,
            new MutableSessionManager("ses_main", "leaf-1"),
            new MemoryConsolidationTrigger(1_200_000L, 30),
            new RecordingRunner(),
            Runnable::run,
            auditSink
        ).start();

        eventBus.publish(new TurnEndEvent(
            "ses_other",
            "turn_1",
            "COMPLETED",
            NOW,
            NOW.plusMillis(1_500_000L),
            1_500_000L,
            31,
            NOW.plusMillis(1_500_000L)
        ));

        assertThat(auditSink.stages())
            .containsExactly(MemoryConsolidationAuditStage.ELIGIBLE, MemoryConsolidationAuditStage.SKIPPED_SESSION_MISMATCH);
    }

    @Test
    void auditsMissingForkPoint() {
        InMemoryEventBus eventBus = new InMemoryEventBus();
        RecordingAuditSink auditSink = new RecordingAuditSink();
        new MemoryConsolidationTurnEndListener(
            eventBus,
            new MutableSessionManager("ses_main", ""),
            new MemoryConsolidationTrigger(1_200_000L, 30),
            new RecordingRunner(),
            Runnable::run,
            auditSink
        ).start();

        eventBus.publish(completedEvent(1_000L, 31));

        assertThat(auditSink.stages())
            .containsExactly(MemoryConsolidationAuditStage.ELIGIBLE, MemoryConsolidationAuditStage.SKIPPED_NO_FORK_POINT);
    }

    @Test
    void runnerFailureDoesNotEscapeEventPublish() {
        InMemoryEventBus eventBus = new InMemoryEventBus();
        MemoryConsolidationRunner runner = request -> {
            throw new IllegalStateException("background failure");
        };
        RecordingAuditSink auditSink = new RecordingAuditSink();
        new MemoryConsolidationTurnEndListener(
            eventBus,
            new MutableSessionManager("ses_main", "leaf-1"),
            new MemoryConsolidationTrigger(1_200_000L, 30),
            runner,
            Runnable::run,
            auditSink
        ).start();

        eventBus.publish(completedEvent(1_000L, 31));

        assertThat(auditSink.stages()).contains(
            MemoryConsolidationAuditStage.ELIGIBLE,
            MemoryConsolidationAuditStage.SUBMITTED,
            MemoryConsolidationAuditStage.RUNNER_FAILED
        );
        assertThat(auditSink.record(MemoryConsolidationAuditStage.RUNNER_FAILED).error()).contains("IllegalStateException");
    }

    @Test
    void executorRejectionDoesNotEscapeEventPublish() {
        InMemoryEventBus eventBus = new InMemoryEventBus();
        RecordingRunner runner = new RecordingRunner();
        RecordingAuditSink auditSink = new RecordingAuditSink();
        new MemoryConsolidationTurnEndListener(
            eventBus,
            new MutableSessionManager("ses_main", "leaf-1"),
            new MemoryConsolidationTrigger(1_200_000L, 30),
            runner,
            command -> {
                throw new java.util.concurrent.RejectedExecutionException("closed");
            },
            auditSink
        ).start();

        eventBus.publish(completedEvent(1_000L, 31));

        assertThat(runner.requests).isEmpty();
        assertThat(auditSink.stages()).contains(
            MemoryConsolidationAuditStage.ELIGIBLE,
            MemoryConsolidationAuditStage.SUBMIT_REJECTED
        );
    }

    @Test
    void quietEventBusDropsPublishedEventsAndSubscriptions() {
        QuietEventBus quiet = new QuietEventBus();
        List<Object> delivered = new ArrayList<>();
        quiet.subscribe(new EventFilter(Optional.empty(), Optional.empty()), envelope -> delivered.add(envelope));

        quiet.publish(completedEvent(1_000L, 31));

        assertThat(delivered).isEmpty();
    }

    private TurnEndEvent completedEvent(long durationMillis, int toolRounds) {
        return new TurnEndEvent(
            "ses_main",
            "turn_1",
            "COMPLETED",
            NOW,
            NOW.plusMillis(durationMillis),
            durationMillis,
            toolRounds,
            NOW.plusMillis(durationMillis)
        );
    }

    private static final class RecordingRunner implements MemoryConsolidationRunner {
        private final List<MemoryConsolidationRequest> requests = new ArrayList<>();

        @Override
        public void run(MemoryConsolidationRequest request) {
            requests.add(request);
        }
    }

    private static final class RecordingAuditSink implements MemoryConsolidationAuditSink {
        private final List<MemoryConsolidationAuditRecord> records = new ArrayList<>();

        @Override
        public void record(MemoryConsolidationAuditRecord record) {
            records.add(record);
        }

        private List<MemoryConsolidationAuditStage> stages() {
            return records.stream()
                .map(MemoryConsolidationAuditRecord::stage)
                .toList();
        }

        private MemoryConsolidationAuditRecord record(MemoryConsolidationAuditStage stage) {
            return records.stream()
                .filter(record -> record.stage() == stage)
                .findFirst()
                .orElseThrow();
        }
    }

    private static final class MutableSessionManager implements SessionManagerPort {
        private final String sessionId;
        private String leafId;

        private MutableSessionManager(String sessionId, String leafId) {
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
            throw new UnsupportedOperationException();
        }

        @Override
        public List<SessionEntry> branch(String leafId) {
            throw new UnsupportedOperationException();
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
            throw new UnsupportedOperationException();
        }

        @Override
        public SessionContext context(String leafId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SessionHandle appendMessage(AgentMessage message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BranchSummaryPlan collectBranchSummaryPlan(String oldLeafId, String targetLeafId) {
            return SessionManagerPort.super.collectBranchSummaryPlan(oldLeafId, targetLeafId);
        }

        @Override
        public SessionHandle fork(ForkRequest request) {
            throw new UnsupportedOperationException();
        }
    }
}
