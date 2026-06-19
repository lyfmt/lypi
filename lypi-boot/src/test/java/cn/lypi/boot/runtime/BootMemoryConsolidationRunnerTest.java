package cn.lypi.boot.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lypi.contracts.agent.TurnRequest;
import cn.lypi.contracts.agent.TurnState;
import cn.lypi.contracts.agent.TurnStatus;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.runtime.AgentCoreFactoryPort;
import cn.lypi.contracts.runtime.AgentCorePort;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.session.CustomMessageEntry;
import cn.lypi.contracts.session.BranchSummaryPlan;
import cn.lypi.contracts.session.ForkRequest;
import cn.lypi.contracts.session.SessionContext;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.session.SessionView;
import cn.lypi.runtime.memory.MemoryConsolidationPromptFactory;
import cn.lypi.runtime.memory.MemoryConsolidationRequest;
import cn.lypi.runtime.memory.MemoryConsolidationAuditRecord;
import cn.lypi.runtime.memory.MemoryConsolidationAuditSink;
import cn.lypi.runtime.memory.MemoryConsolidationAuditStage;
import cn.lypi.session.SessionManagerImpl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BootMemoryConsolidationRunnerTest {
    @TempDir
    Path tempDir;

    @Test
    void forksFromCapturedLeafExecutesPromptAndCleansTemporarySession() {
        SessionManagerImpl mainSession = new SessionManagerImpl(tempDir);
        mainSession.openOrCreate("ses_main");
        mainSession.append(new CustomMessageEntry("root", null, "root", Instant.parse("2026-06-01T00:00:00Z")));
        RecordingAgentCore core = new RecordingAgentCore(TurnStatus.COMPLETED);
        RecordingAgentCoreFactory factory = new RecordingAgentCoreFactory(core);
        RecordingAuditSink auditSink = new RecordingAuditSink();
        BootMemoryConsolidationRunner runner = new BootMemoryConsolidationRunner(
            tempDir,
            mainSession,
            factory,
            new MemoryConsolidationPromptFactory(),
            auditSink
        );

        runner.run(new MemoryConsolidationRequest("ses_main", "root"));

        assertThat(factory.cwd).isEqualTo(tempDir);
        assertThat(factory.sessionManager).isNotSameAs(mainSession);
        assertThat(core.request.get().sessionId()).isNotEqualTo("ses_main");
        assertThat(core.request.get().parentEntryId()).contains("root");
        assertThat(core.request.get().userInput()).contains("memory-settlement");
        assertThat(factory.forkSessionFile.get()).doesNotExist();
        assertThat(auditSink.stages()).containsExactly(
            MemoryConsolidationAuditStage.RUN_STARTED,
            MemoryConsolidationAuditStage.FORK_CREATED,
            MemoryConsolidationAuditStage.TURN_COMPLETED,
            MemoryConsolidationAuditStage.CLEANED
        );
        assertThat(auditSink.record(MemoryConsolidationAuditStage.FORK_CREATED).forkSessionId())
            .isEqualTo(core.request.get().sessionId());
    }

    @Test
    void cleansTemporarySessionWhenBackgroundTurnFails() {
        SessionManagerImpl mainSession = new SessionManagerImpl(tempDir);
        mainSession.openOrCreate("ses_main");
        mainSession.append(new CustomMessageEntry("root", null, "root", Instant.parse("2026-06-01T00:00:00Z")));
        RecordingAgentCore core = new RecordingAgentCore(TurnStatus.FAILED);
        RecordingAgentCoreFactory factory = new RecordingAgentCoreFactory(core);
        RecordingAuditSink auditSink = new RecordingAuditSink();
        BootMemoryConsolidationRunner runner = new BootMemoryConsolidationRunner(
            tempDir,
            mainSession,
            factory,
            new MemoryConsolidationPromptFactory(),
            auditSink
        );

        runner.run(new MemoryConsolidationRequest("ses_main", "root"));

        assertThat(core.executed.get()).isTrue();
        assertThat(factory.forkSessionFile.get()).doesNotExist();
        assertThat(auditSink.stages()).contains(MemoryConsolidationAuditStage.TURN_COMPLETED);
        assertThat(auditSink.record(MemoryConsolidationAuditStage.TURN_COMPLETED).reason()).contains("FAILED");
    }

    @Test
    void forkSessionManagerInheritsForkPointRuntimeContext() {
        PermissionRuntimeState permissionRuntimeState = PermissionRuntimeState.fromLegacy(PermissionMode.BYPASS);
        ModelSelection model = new ModelSelection("openai", "gpt-5.4", ThinkingLevel.HIGH);
        SessionManagerImpl mainSession = new SessionManagerImpl(
            tempDir,
            model,
            ThinkingLevel.HIGH,
            AgentMode.PLAN,
            permissionRuntimeState
        );
        mainSession.openOrCreate("ses_main");
        mainSession.append(new CustomMessageEntry("root", null, "root", Instant.parse("2026-06-01T00:00:00Z")));
        RecordingAgentCore core = new RecordingAgentCore(TurnStatus.COMPLETED);
        RecordingAgentCoreFactory factory = new RecordingAgentCoreFactory(core);
        BootMemoryConsolidationRunner runner = new BootMemoryConsolidationRunner(
            tempDir,
            mainSession,
            factory,
            new MemoryConsolidationPromptFactory(),
            new RecordingAuditSink()
        );

        runner.run(new MemoryConsolidationRequest("ses_main", "root"));

        SessionContext forkContext = factory.forkContext.get();
        assertThat(forkContext.model()).isEqualTo(model);
        assertThat(forkContext.thinkingLevel()).isEqualTo(ThinkingLevel.HIGH);
        assertThat(forkContext.mode()).isEqualTo(AgentMode.PLAN);
        assertThat(forkContext.permissionRuntimeState()).isEqualTo(permissionRuntimeState);
    }

    @Test
    void cleansTemporarySessionWhenOpeningForkSessionFails() {
        SessionManagerImpl mainSession = new SessionManagerImpl(tempDir);
        mainSession.openOrCreate("ses_main");
        mainSession.append(new CustomMessageEntry("root", null, "root", Instant.parse("2026-06-01T00:00:00Z")));
        FailingOpenSessionManager forkSessionManager = new FailingOpenSessionManager();
        RecordingAuditSink auditSink = new RecordingAuditSink();
        BootMemoryConsolidationRunner runner = new BootMemoryConsolidationRunner(
            tempDir,
            mainSession,
            new RecordingAgentCoreFactory(new RecordingAgentCore(TurnStatus.COMPLETED)),
            new MemoryConsolidationPromptFactory(),
            auditSink,
            (cwd, context) -> forkSessionManager
        );

        assertThatThrownBy(() -> runner.run(new MemoryConsolidationRequest("ses_main", "root")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("open failed");

        assertThat(forkSessionManager.deletedSessionId.get()).startsWith("ses_");
        assertThat(auditSink.stages()).contains(
            MemoryConsolidationAuditStage.RUN_STARTED,
            MemoryConsolidationAuditStage.FORK_CREATED,
            MemoryConsolidationAuditStage.RUN_FAILED,
            MemoryConsolidationAuditStage.CLEANED
        );
        assertThat(auditSink.record(MemoryConsolidationAuditStage.RUN_FAILED).error()).contains("IllegalStateException");
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

    private static final class RecordingAgentCoreFactory implements AgentCoreFactoryPort {
        private final RecordingAgentCore core;
        private Path cwd;
        private SessionManagerPort sessionManager;
        private final AtomicReference<Path> forkSessionFile = new AtomicReference<>();
        private final AtomicReference<SessionContext> forkContext = new AtomicReference<>();

        private RecordingAgentCoreFactory(RecordingAgentCore core) {
            this.core = core;
        }

        @Override
        public AgentCorePort create(Path cwd, SessionManagerPort sessionManager) {
            this.cwd = cwd;
            this.sessionManager = sessionManager;
            forkContext.set(sessionManager.context(sessionManager.currentView().leafId()));
            forkSessionFile.set(sessionManager.currentView().sessionId() == null
                ? null
                : cwd.resolve(".ly-pi").resolve("sessions").resolve(sessionManager.currentView().sessionId() + ".jsonl"));
            return core;
        }
    }

    private static final class RecordingAgentCore implements AgentCorePort {
        private final TurnStatus status;
        private final AtomicBoolean executed = new AtomicBoolean();
        private final AtomicReference<TurnRequest> request = new AtomicReference<>();

        private RecordingAgentCore(TurnStatus status) {
            this.status = status;
        }

        @Override
        public TurnState execute(TurnRequest request) {
            executed.set(true);
            this.request.set(request);
            AgentMessage message = new AgentMessage(
                "msg_assistant",
                MessageRole.ASSISTANT,
                MessageKind.TEXT,
                List.of(new TextContentBlock("done")),
                Instant.now(),
                Optional.empty(),
                Optional.empty()
            );
            return new TurnState(
                "turn_background",
                request.sessionId(),
                new ContextSnapshot(
                    null,
                    List.of(),
                    new ModelSelection("test", "model", ThinkingLevel.LOW),
                    ThinkingLevel.LOW,
                    AgentMode.EXECUTE,
                    PermissionMode.DEFAULT_EXECUTE,
                    null
                ),
                new ArrayList<>(List.of(message)),
                0,
                status
            );
        }
    }

    private static final class FailingOpenSessionManager implements SessionManagerPort {
        private final AtomicReference<String> deletedSessionId = new AtomicReference<>();

        @Override
        public SessionHandle openOrCreate(String sessionId) {
            throw new IllegalStateException("open failed");
        }

        @Override
        public SessionHandle append(SessionEntry entry) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SessionHandle switchLeaf(String leafId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<SessionEntry> branch(String leafId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BranchSummaryPlan collectBranchSummaryPlan(String oldLeafId, String targetLeafId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SessionView currentView() {
            throw new UnsupportedOperationException();
        }

        @Override
        public SessionView view(String leafId) {
            throw new UnsupportedOperationException();
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
        public SessionHandle appendBranchSummary(String parentId, String fromId, String summary) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SessionHandle fork(ForkRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteSession(String sessionId) {
            deletedSessionId.set(sessionId);
        }
    }
}
