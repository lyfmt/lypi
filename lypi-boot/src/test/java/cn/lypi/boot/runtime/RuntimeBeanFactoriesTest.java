package cn.lypi.boot.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.context.AgentMessage;
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
import cn.lypi.contracts.tui.SessionRuntimeState;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
            return List.of();
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
}
