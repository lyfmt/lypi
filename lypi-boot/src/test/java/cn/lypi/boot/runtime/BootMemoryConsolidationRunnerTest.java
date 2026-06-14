package cn.lypi.boot.runtime;

import static org.assertj.core.api.Assertions.assertThat;

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
import cn.lypi.contracts.session.CustomMessageEntry;
import cn.lypi.runtime.memory.MemoryConsolidationPromptFactory;
import cn.lypi.runtime.memory.MemoryConsolidationRequest;
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
        BootMemoryConsolidationRunner runner = new BootMemoryConsolidationRunner(
            tempDir,
            mainSession,
            factory,
            new MemoryConsolidationPromptFactory()
        );

        runner.run(new MemoryConsolidationRequest("ses_main", "root"));

        assertThat(factory.cwd).isEqualTo(tempDir);
        assertThat(factory.sessionManager).isNotSameAs(mainSession);
        assertThat(core.request.get().sessionId()).isNotEqualTo("ses_main");
        assertThat(core.request.get().parentEntryId()).contains("root");
        assertThat(core.request.get().userInput()).contains("memory-settlement");
        assertThat(factory.forkSessionFile.get()).doesNotExist();
    }

    @Test
    void cleansTemporarySessionWhenBackgroundTurnFails() {
        SessionManagerImpl mainSession = new SessionManagerImpl(tempDir);
        mainSession.openOrCreate("ses_main");
        mainSession.append(new CustomMessageEntry("root", null, "root", Instant.parse("2026-06-01T00:00:00Z")));
        RecordingAgentCore core = new RecordingAgentCore(TurnStatus.FAILED);
        RecordingAgentCoreFactory factory = new RecordingAgentCoreFactory(core);
        BootMemoryConsolidationRunner runner = new BootMemoryConsolidationRunner(
            tempDir,
            mainSession,
            factory,
            new MemoryConsolidationPromptFactory()
        );

        runner.run(new MemoryConsolidationRequest("ses_main", "root"));

        assertThat(core.executed.get()).isTrue();
        assertThat(factory.forkSessionFile.get()).doesNotExist();
    }

    private static final class RecordingAgentCoreFactory implements AgentCoreFactoryPort {
        private final RecordingAgentCore core;
        private Path cwd;
        private SessionManagerPort sessionManager;
        private final AtomicReference<Path> forkSessionFile = new AtomicReference<>();

        private RecordingAgentCoreFactory(RecordingAgentCore core) {
            this.core = core;
        }

        @Override
        public AgentCorePort create(Path cwd, SessionManagerPort sessionManager) {
            this.cwd = cwd;
            this.sessionManager = sessionManager;
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
}
