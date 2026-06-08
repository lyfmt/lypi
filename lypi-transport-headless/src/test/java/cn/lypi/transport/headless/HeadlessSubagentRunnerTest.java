package cn.lypi.transport.headless;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.agent.TurnRequest;
import cn.lypi.contracts.agent.TurnState;
import cn.lypi.contracts.agent.TurnStatus;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.runtime.AgentCorePort;
import cn.lypi.contracts.runtime.SessionManagerFactoryPort;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.session.ForkRequest;
import cn.lypi.contracts.session.SessionContext;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.session.SessionView;
import cn.lypi.contracts.subagent.HeadlessSubagentOutput;
import cn.lypi.contracts.subagent.SubagentRunStatus;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HeadlessSubagentRunnerTest {
    @Test
    void runReadsJsonExecutesChildTurnAndWritesJsonOutput() {
        CapturingAgentCore agentCore = new CapturingAgentCore(TurnStatus.COMPLETED, "child final answer");
        CapturingSessionFactory sessionFactory = new CapturingSessionFactory("entry_final");
        HeadlessSubagentJsonCodec codec = new HeadlessSubagentJsonCodec();
        HeadlessSubagentRunner runner = new HeadlessSubagentRunner(agentCore, sessionFactory, codec);
        String json = """
            {
              "childSessionId": "ses_child",
              "parentSessionId": "ses_parent",
              "parentSpawnEntryId": "entry_spawn",
              "prompt": "请审查代码",
              "cwd": "/tmp/project",
              "allowedTools": [],
              "permissionMode": "DEFAULT_EXECUTE",
              "timeoutSeconds": 30
            }
            """;
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        runner.run(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), out);
        HeadlessSubagentOutput output = codec.readOutput(new ByteArrayInputStream(out.toByteArray()));

        assertThat(sessionFactory.openedCwd).isEqualTo(Path.of("/tmp/project"));
        assertThat(sessionFactory.openedSessionId).isEqualTo("ses_child");
        assertThat(agentCore.request.sessionId()).isEqualTo("ses_child");
        assertThat(agentCore.request.userInput()).isEqualTo("请审查代码");
        assertThat(output.childSessionId()).isEqualTo("ses_child");
        assertThat(output.status()).isEqualTo(SubagentRunStatus.SUCCEEDED);
        assertThat(output.summary()).isEqualTo("child final answer");
        assertThat(output.finalEntryId()).contains("msg_final");
    }

    @Test
    void runWritesStructuredFailureForInvalidInput() {
        HeadlessSubagentJsonCodec codec = new HeadlessSubagentJsonCodec();
        HeadlessSubagentRunner runner = new HeadlessSubagentRunner(
            new CapturingAgentCore(TurnStatus.COMPLETED, "unused"),
            new CapturingSessionFactory("entry_final"),
            codec
        );
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        runner.run(new ByteArrayInputStream("not-json".getBytes(StandardCharsets.UTF_8)), out);
        HeadlessSubagentOutput output = codec.readOutput(new ByteArrayInputStream(out.toByteArray()));

        assertThat(output.status()).isEqualTo(SubagentRunStatus.FAILED);
        assertThat(output.errorMessage()).isPresent();
    }

    private static final class CapturingAgentCore implements AgentCorePort {
        private final TurnStatus status;
        private final String finalText;
        private TurnRequest request;

        private CapturingAgentCore(TurnStatus status, String finalText) {
            this.status = status;
            this.finalText = finalText;
        }

        @Override
        public TurnState execute(TurnRequest request) {
            this.request = request;
            AgentMessage message = new AgentMessage(
                "msg_final",
                MessageRole.ASSISTANT,
                MessageKind.TEXT,
                List.<ContentBlock>of(new TextContentBlock(finalText, Map.of())),
                Instant.parse("2026-06-09T00:00:00Z"),
                Optional.empty(),
                Optional.empty()
            );
            return new TurnState("turn_child", request.sessionId(), null, List.of(message), 0, status);
        }
    }

    private static final class CapturingSessionFactory implements SessionManagerFactoryPort {
        private final String leafId;
        private Path openedCwd;
        private String openedSessionId;

        private CapturingSessionFactory(String leafId) {
            this.leafId = leafId;
        }

        @Override
        public SessionManagerPort open(Path cwd, String sessionId) {
            this.openedCwd = cwd;
            this.openedSessionId = sessionId;
            return new MinimalSessionManager(sessionId, leafId);
        }
    }

    private record MinimalSessionManager(String sessionId, String leafId) implements SessionManagerPort {
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
        public SessionHandle fork(ForkRequest request) {
            throw new UnsupportedOperationException();
        }
    }
}
