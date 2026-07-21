package cn.lypi.boot.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.runtime.AppEntry;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.session.ForkRequest;
import cn.lypi.contracts.session.SessionContext;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.session.SessionView;
import cn.lypi.contracts.subagent.MailboxMessage;
import cn.lypi.contracts.subagent.MailboxStatus;
import cn.lypi.contracts.subagent.SubagentResultRef;
import cn.lypi.contracts.tui.SessionRuntimeState;
import cn.lypi.runtime.subagent.MailboxDeliveryGuard;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class RuntimeBeanFactoriesTest {
    @Test
    void createsConfiguredSessionRuntimeState() {
        LyPiRuntimeProperties properties = new LyPiRuntimeProperties();
        properties.setCwd(Path.of("/tmp/project"));
        properties.setSessionId("ses_factory");
        RecordingSessionManager sessionManager = new RecordingSessionManager();

        SessionRuntimeState state = RuntimeBeanFactories.sessionRuntimeState(properties, sessionManager);

        assertThat(state.sessionId()).isEqualTo("ses_factory");
        assertThat(state.cwd()).isEqualTo(Path.of("/tmp/project").toAbsolutePath().normalize());
        assertThat(state.permissionRuntimeState()).isEqualTo(sessionManager.permissionRuntimeState);
        assertThat(sessionManager.openedSessionIds).containsExactly("ses_factory");
        assertThat(sessionManager.temporarySessionIds).isEmpty();
    }

    @Test
    void applicationRunnerSkipsHeadlessSubagentArguments() throws Exception {
        LyPiRuntimeProperties properties = new LyPiRuntimeProperties();
        properties.setCwd(Path.of("/tmp/project"));
        properties.setSessionId("ses_runner");
        RecordingAppEntry appEntry = new RecordingAppEntry();

        RuntimeBeanFactories.applicationRunner(appEntry, properties)
            .run(new DefaultApplicationArguments("headless-subagent"));
        RuntimeBeanFactories.applicationRunner(appEntry, properties)
            .run(new DefaultApplicationArguments("--lypi.headless.subagent=true"));

        assertThat(appEntry.requests).isEmpty();
    }

    @Test
    void mailboxDeliveryGuardReadsRuntimeStateWhenCheckingDelivery() {
        RecordingSessionManager sessionManager = new RecordingSessionManager();
        AtomicReference<SessionRuntimeState> runtimeState = new AtomicReference<>();
        MailboxDeliveryGuard guard = RuntimeBeanFactories.mailboxDeliveryGuard(runtimeState::get, sessionManager);

        assertThat(guard.canDeliver(mail("ses_parent"))).isFalse();

        runtimeState.set(runtimeState("ses_parent"));

        assertThat(guard.canDeliver(mail("ses_parent"))).isTrue();
    }

    private static SessionRuntimeState runtimeState(String sessionId) {
        return new SessionRuntimeState(
            sessionId,
            Path.of("/tmp/project").toAbsolutePath().normalize(),
            "entry_spawn",
            new ModelSelection("provider", "model", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.ASK,
            new ContextBudget(0, 0, 0, 0, 0, 0L, 0L, BigDecimal.ZERO),
            false,
            false,
            false,
            false
        );
    }

    private static MailboxMessage mail(String parentSessionId) {
        return new MailboxMessage(
            "mail_1",
            "agent_1",
            "ses_child",
            parentSessionId,
            "entry_spawn",
            "完成摘要",
            new SubagentResultRef("ses_child", "entry_final", Optional.empty()),
            MailboxStatus.PENDING,
            Instant.EPOCH,
            Instant.EPOCH
        );
    }

    private static final class RecordingSessionManager implements cn.lypi.contracts.runtime.SessionManagerPort {
        private final List<String> openedSessionIds = new ArrayList<>();
        private final List<String> temporarySessionIds = new ArrayList<>();
        private final PermissionRuntimeState permissionRuntimeState = PermissionRuntimeState.forMode(PermissionMode.AUTO);

        @Override
        public SessionHandle openOrCreate(String sessionId) {
            openedSessionIds.add(sessionId);
            return new SessionHandle(sessionId, null, "leaf-1", Map.of());
        }

        @Override
        public SessionHandle openTemporary(String sessionId) {
            temporarySessionIds.add(sessionId);
            return new SessionHandle(sessionId, null, "leaf-1", Map.of());
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
            return List.of(new TestEntry("entry_spawn", null, Instant.EPOCH));
        }

        @Override
        public SessionView currentView() {
            return new SessionView("ses_factory", "leaf-1");
        }

        @Override
        public SessionView view(String leafId) {
            return new SessionView("ses_factory", leafId);
        }

        @Override
        public List<AgentMessage> transcript(String leafId) {
            return List.of();
        }

        @Override
        public SessionContext context(String leafId) {
            return new SessionContext(
                List.of(),
                List.of(),
                List.of(),
                new ModelSelection("provider", "model", ThinkingLevel.MEDIUM),
                ThinkingLevel.MEDIUM,
                AgentMode.EXECUTE,
                permissionRuntimeState
            );
        }

        @Override
        public SessionHandle appendMessage(AgentMessage message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SessionHandle fork(ForkRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RecordingAppEntry implements AppEntry {
        private final List<cn.lypi.contracts.bootstrap.BootstrapRequest> requests = new ArrayList<>();

        @Override
        public void start(cn.lypi.contracts.bootstrap.BootstrapRequest request) {
            requests.add(request);
        }
    }

    private record TestEntry(String id, String parentId, Instant timestamp) implements SessionEntry {
    }
}
