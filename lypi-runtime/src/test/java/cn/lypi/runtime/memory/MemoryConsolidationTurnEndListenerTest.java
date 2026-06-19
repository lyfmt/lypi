package cn.lypi.runtime.memory;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.context.ToolResultContentBlock;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
            new MemoryConsolidationTrigger(10, 5, 2),
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
    void usesTurnEndLeafEvenWhenCurrentViewMovedBeforeListenerRuns() {
        InMemoryEventBus eventBus = new InMemoryEventBus();
        MutableSessionManager sessionManager = new MutableSessionManager("ses_main", "leaf-2");
        RecordingRunner runner = new RecordingRunner();
        new MemoryConsolidationTurnEndListener(
            eventBus,
            sessionManager,
            new MemoryConsolidationTrigger(10, 5, 2),
            runner,
            Runnable::run
        ).start();

        eventBus.publish(completedEvent(1_000L, 31, "leaf-1"));

        assertThat(runner.requests)
            .containsExactly(new MemoryConsolidationRequest("ses_main", "leaf-1"));
    }

    @Test
    void skipsFailedTurnEvenWhenThresholdsAreMet() {
        InMemoryEventBus eventBus = new InMemoryEventBus();
        RecordingRunner runner = new RecordingRunner();
        new MemoryConsolidationTurnEndListener(
            eventBus,
            new MutableSessionManager("ses_main", "leaf-1", List.of(textMessage("short", "short"))),
            new MemoryConsolidationTrigger(10, 5, 2),
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
            new MutableSessionManager("ses_main", "leaf-1", List.of(textMessage("short", "short"))),
            new MemoryConsolidationTrigger(10, 5, 2),
            new RecordingRunner(),
            Runnable::run,
            auditSink
        ).start();

        eventBus.publish(completedEvent(1_000L, 30));

        assertThat(auditSink.stages()).containsExactly(
            MemoryConsolidationAuditStage.ELIGIBLE,
            MemoryConsolidationAuditStage.SKIPPED_THRESHOLD,
            MemoryConsolidationAuditStage.SUBMITTED
        );
    }

    @Test
    void skipsTurnFromAnotherSession() {
        InMemoryEventBus eventBus = new InMemoryEventBus();
        RecordingRunner runner = new RecordingRunner();
        new MemoryConsolidationTurnEndListener(
            eventBus,
            new MutableSessionManager("ses_main", "leaf-1"),
            new MemoryConsolidationTrigger(10, 5, 2),
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
            new MemoryConsolidationTrigger(10, 5, 2),
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
            new MemoryConsolidationTrigger(10, 5, 2),
            new RecordingRunner(),
            Runnable::run,
            auditSink
        ).start();

        eventBus.publish(completedEvent(1_000L, 31, ""));

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
            new MemoryConsolidationTrigger(10, 5, 2),
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
            new MemoryConsolidationTrigger(10, 5, 2),
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
    void skipsBackgroundRunWhenMainTurnAlreadyWroteMemory() {
        InMemoryEventBus eventBus = new InMemoryEventBus();
        RecordingRunner runner = new RecordingRunner();
        RecordingAuditSink auditSink = new RecordingAuditSink();
        new MemoryConsolidationTurnEndListener(
            eventBus,
            new MutableSessionManager(
                "ses_main",
                "leaf-1",
                List.of(
                    textMessage("context", "0123456789012345678901234567890123456789"),
                    assistantToolCall("read", Map.of("path", "README.md"), true, "toolu-0"),
                    toolResult(false, "toolu-0"),
                    assistantToolCall("edit", Map.of("path", ".ly-pi/memory/project/facts.md"), true),
                    toolResult(false)
                )
            ),
            new MemoryConsolidationTrigger(10, 5, 2),
            runner,
            Runnable::run,
            auditSink
        ).start();

        eventBus.publish(completedEvent(1_000L, 31));

        assertThat(runner.requests).isEmpty();
        assertThat(auditSink.stages()).contains(
            MemoryConsolidationAuditStage.ELIGIBLE,
            MemoryConsolidationAuditStage.SUBMITTED,
            MemoryConsolidationAuditStage.SKIPPED_DIRECT_WRITE
        );
    }

    @Test
    void doesNotReadTranscriptBeforeSubmittingBackgroundTask() {
        InMemoryEventBus eventBus = new InMemoryEventBus();
        RecordingAuditSink auditSink = new RecordingAuditSink();
        FailingTranscriptSessionManager sessionManager = new FailingTranscriptSessionManager("ses_main", "leaf-1");
        List<Runnable> queued = new ArrayList<>();
        new MemoryConsolidationTurnEndListener(
            eventBus,
            sessionManager,
            new MemoryConsolidationTrigger(10, 5, 2),
            new RecordingRunner(),
            queued::add,
            auditSink
        ).start();

        eventBus.publish(completedEvent(1_000L, 31));

        assertThat(queued).hasSize(1);
        assertThat(auditSink.stages()).containsExactly(
            MemoryConsolidationAuditStage.ELIGIBLE,
            MemoryConsolidationAuditStage.SUBMITTED
        );
    }

    @Test
    void submitsBackgroundRunWhenMainTurnMemoryWriteFailed() {
        InMemoryEventBus eventBus = new InMemoryEventBus();
        RecordingRunner runner = new RecordingRunner();
        RecordingAuditSink auditSink = new RecordingAuditSink();
        new MemoryConsolidationTurnEndListener(
            eventBus,
            new MutableSessionManager(
                "ses_main",
                "leaf-1",
                List.of(
                    textMessage("context", "0123456789012345678901234567890123456789"),
                    assistantToolCall("read", Map.of("path", "README.md"), true, "toolu-0"),
                    toolResult(false, "toolu-0"),
                    assistantToolCall("write", Map.of("path", ".ly-pi/memory/project/facts.md"), true),
                    toolResult(true)
                )
            ),
            new MemoryConsolidationTrigger(10, 5, 2),
            runner,
            Runnable::run,
            auditSink
        ).start();

        eventBus.publish(completedEvent(1_000L, 31));

        assertThat(runner.requests).containsExactly(new MemoryConsolidationRequest("ses_main", "leaf-1"));
        assertThat(auditSink.stages()).doesNotContain(MemoryConsolidationAuditStage.SKIPPED_DIRECT_WRITE);
    }

    @Test
    void skipsBackgroundRunWhenMainTurnBashWroteMemory() {
        InMemoryEventBus eventBus = new InMemoryEventBus();
        RecordingRunner runner = new RecordingRunner();
        RecordingAuditSink auditSink = new RecordingAuditSink();
        new MemoryConsolidationTurnEndListener(
            eventBus,
            new MutableSessionManager(
                "ses_main",
                "leaf-1",
                List.of(
                    textMessage("context", "0123456789012345678901234567890123456789"),
                    assistantToolCall("read", Map.of("path", "README.md"), true, "toolu-0"),
                    toolResult(false, "toolu-0"),
                    assistantToolCall("bash", Map.of("command", "printf fact >> .ly-pi/memory/project/facts.md"), true),
                    toolResult(false)
                )
            ),
            new MemoryConsolidationTrigger(10, 5, 2),
            runner,
            Runnable::run,
            auditSink
        ).start();

        eventBus.publish(completedEvent(1_000L, 31));

        assertThat(runner.requests).isEmpty();
        assertThat(auditSink.stages()).contains(MemoryConsolidationAuditStage.SKIPPED_DIRECT_WRITE);
    }

    @Test
    void coalescesRequestsWhileProjectRunIsActive() {
        InMemoryEventBus eventBus = new InMemoryEventBus();
        RecordingRunner runner = new RecordingRunner();
        RecordingAuditSink auditSink = new RecordingAuditSink();
        List<Runnable> queued = new ArrayList<>();
        Executor queuedExecutor = queued::add;
        MutableSessionManager sessionManager = new LeafTranscriptSessionManager(
            "ses_main",
            "leaf-1",
            Map.of(
                "leaf-1",
                List.of(textMessage("context-1", "0123456789012345678901234567890123456789")),
                "leaf-2",
                List.of(
                    textMessage("context-1", "0123456789012345678901234567890123456789"),
                    textMessage("context-2", "012345678901234567890123456789")
                )
            )
        );
        new MemoryConsolidationTurnEndListener(
            eventBus,
            sessionManager,
            new MemoryConsolidationTrigger(10, 5, 2),
            runner,
            queuedExecutor,
            auditSink
        ).start();

        eventBus.publish(completedEvent(1_000L, 31));
        sessionManager.leafId = "leaf-2";
        eventBus.publish(completedEvent(1_000L, 31, "leaf-2"));

        assertThat(queued).hasSize(1);
        assertThat(runner.requests).isEmpty();
        assertThat(auditSink.stages()).contains(MemoryConsolidationAuditStage.COALESCED);

        queued.getFirst().run();

        assertThat(runner.requests)
            .containsExactly(
                new MemoryConsolidationRequest("ses_main", "leaf-1"),
                new MemoryConsolidationRequest("ses_main", "leaf-2")
            );
    }

    @Test
    void submitsBackgroundRunWhenDirectWriteDetectionFails() {
        InMemoryEventBus eventBus = new InMemoryEventBus();
        RecordingRunner runner = new RecordingRunner();
        RecordingAuditSink auditSink = new RecordingAuditSink();
        new MemoryConsolidationTurnEndListener(
            eventBus,
            new FailingTranscriptSessionManager("ses_main", "leaf-1"),
            new MemoryConsolidationTrigger(10, 5, 2),
            runner,
            Runnable::run,
            auditSink
        ).start();

        eventBus.publish(completedEvent(1_000L, 31));

        assertThat(runner.requests).containsExactly(new MemoryConsolidationRequest("ses_main", "leaf-1"));
        assertThat(auditSink.stages()).contains(
            MemoryConsolidationAuditStage.DIRECT_WRITE_DETECTION_FAILED,
            MemoryConsolidationAuditStage.SUBMITTED
        );
        assertThat(auditSink.record(MemoryConsolidationAuditStage.DIRECT_WRITE_DETECTION_FAILED).reason())
            .contains("background memory gate transcript read failed");
    }

    @Test
    void closeWaitsForActiveBackgroundRunToFinish() throws Exception {
        InMemoryEventBus eventBus = new InMemoryEventBus();
        CountDownLatch runnerStarted = new CountDownLatch(1);
        CountDownLatch allowRunnerToFinish = new CountDownLatch(1);
        CountDownLatch runnerFinished = new CountDownLatch(1);
        Executor threadExecutor = command -> {
            Thread thread = new Thread(command, "memory-listener-test");
            thread.start();
        };
        MemoryConsolidationRunner runner = request -> {
            runnerStarted.countDown();
            try {
                assertThat(allowRunnerToFinish.await(1, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            } finally {
                runnerFinished.countDown();
            }
        };
        MemoryConsolidationTurnEndListener listener = new MemoryConsolidationTurnEndListener(
            eventBus,
            new MutableSessionManager("ses_main", "leaf-1"),
            new MemoryConsolidationTrigger(10, 5, 2),
            runner,
            threadExecutor
        );
        listener.start();

        eventBus.publish(completedEvent(1_000L, 31));
        assertThat(runnerStarted.await(1, TimeUnit.SECONDS)).isTrue();

        Thread closeThread = new Thread(listener::close, "memory-listener-close-test");
        closeThread.start();
        assertThat(runnerFinished.await(100, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(closeThread.isAlive()).isTrue();

        allowRunnerToFinish.countDown();

        closeThread.join(1_000L);
        assertThat(closeThread.isAlive()).isFalse();
        assertThat(runnerFinished.await(1, TimeUnit.SECONDS)).isTrue();
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
        return completedEvent(durationMillis, toolRounds, "leaf-1");
    }

    private TurnEndEvent completedEvent(long durationMillis, int toolRounds, String leafEntryId) {
        return new TurnEndEvent(
            "ses_main",
            "turn_1",
            "COMPLETED",
            NOW,
            NOW.plusMillis(durationMillis),
            durationMillis,
            toolRounds,
            NOW.plusMillis(durationMillis),
            leafEntryId
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

    private static AgentMessage assistantToolCall(String toolName, Map<String, Object> input, boolean complete) {
        return assistantToolCall(toolName, input, complete, "toolu-1");
    }

    private static AgentMessage assistantToolCall(String toolName, Map<String, Object> input, boolean complete, String toolUseId) {
        return new AgentMessage(
            "msg-tool-call",
            MessageRole.ASSISTANT,
            MessageKind.TOOL_CALL,
            List.of(new ToolCallContentBlock(toolUseId, toolName, "", Map.of("input", input, "complete", complete))),
            NOW,
            Optional.empty(),
            Optional.empty()
        );
    }

    private static AgentMessage toolResult(boolean error) {
        return toolResult(error, "toolu-1");
    }

    private static AgentMessage toolResult(boolean error, String toolUseId) {
        return new AgentMessage(
            "msg-tool-result",
            MessageRole.TOOL_RESULT,
            MessageKind.TOOL_RESULT,
            List.of(new ToolResultContentBlock(toolUseId, error ? "failed" : "ok", error)),
            NOW,
            Optional.empty(),
            Optional.empty()
        );
    }

    private static AgentMessage textMessage(String id, String text) {
        return new AgentMessage(
            id,
            MessageRole.ASSISTANT,
            MessageKind.TEXT,
            List.of(new TextContentBlock(text)),
            NOW,
            Optional.empty(),
            Optional.empty()
        );
    }

    private static class MutableSessionManager implements SessionManagerPort {
        private final String sessionId;
        private String leafId;
        private final List<AgentMessage> transcript;

        private MutableSessionManager(String sessionId, String leafId) {
            this(sessionId, leafId, List.of(textMessage("context", "0123456789012345678901234567890123456789")));
        }

        private MutableSessionManager(String sessionId, String leafId, List<AgentMessage> transcript) {
            this.sessionId = sessionId;
            this.leafId = leafId;
            this.transcript = transcript;
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
            return transcript;
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

    private static final class FailingTranscriptSessionManager extends MutableSessionManager {
        private FailingTranscriptSessionManager(String sessionId, String leafId) {
            super(sessionId, leafId);
        }

        @Override
        public List<AgentMessage> transcript(String leafId) {
            throw new IllegalStateException("transcript failed");
        }
    }

    private static final class LeafTranscriptSessionManager extends MutableSessionManager {
        private final Map<String, List<AgentMessage>> transcripts;

        private LeafTranscriptSessionManager(String sessionId, String leafId, Map<String, List<AgentMessage>> transcripts) {
            super(sessionId, leafId);
            this.transcripts = transcripts;
        }

        @Override
        public List<AgentMessage> transcript(String leafId) {
            return transcripts.getOrDefault(leafId, List.of());
        }
    }
}
