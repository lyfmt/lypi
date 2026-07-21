package cn.lypi.transport.headless;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lypi.contracts.agent.TurnRequest;
import cn.lypi.contracts.agent.TurnState;
import cn.lypi.contracts.agent.TurnStatus;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.runtime.AgentCoreFactoryPort;
import cn.lypi.contracts.runtime.AgentCorePort;
import cn.lypi.contracts.runtime.SessionManagerFactoryPort;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.session.ForkRequest;
import cn.lypi.contracts.session.SessionContext;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.session.SessionView;
import cn.lypi.contracts.subagent.HeadlessSubagentInput;
import cn.lypi.contracts.subagent.HeadlessSubagentOutput;
import cn.lypi.contracts.subagent.SubagentRunStatus;
import cn.lypi.contracts.subagent.SubagentToolPolicy;
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
    void executesExactlyOnePromptOnlyTurnAndReturnsIdentity() {
        CapturingCoreFactory coreFactory = new CapturingCoreFactory(TurnStatus.COMPLETED, "inspection complete");
        CapturingSessionFactory sessions = new CapturingSessionFactory();
        HeadlessSubagentRunner runner = new HeadlessSubagentRunner(coreFactory, sessions, new HeadlessSubagentJsonCodec());

        HeadlessSubagentOutput output = runner.execute(input());

        assertThat(coreFactory.cwd).isEqualTo(Path.of("/tmp/project"));
        assertThat(coreFactory.policy.effectiveTools()).containsExactly("read", "grep", "glob");
        assertThat(coreFactory.request.userInput()).isEqualTo("inspect session");
        assertThat(coreFactory.request.parentEntryId()).isEmpty();
        assertThat(coreFactory.request.skillMentions()).isEmpty();
        assertThat(output.taskName()).isEqualTo("inspect-session");
        assertThat(output.agentId()).isEqualTo("agent_1");
        assertThat(output.childSessionId()).isEqualTo("ses_child");
        assertThat(output.runId()).isEqualTo("run_1");
        assertThat(output.status()).isEqualTo(SubagentRunStatus.SUCCEEDED);
        assertThat(output.content()).isEqualTo("inspection complete");
    }

    @Test
    void failedChildTurnReturnsStructuredFailureWithSameIdentity() {
        HeadlessSubagentRunner runner = new HeadlessSubagentRunner(
            new CapturingCoreFactory(TurnStatus.FAILED, "permission denied"),
            new CapturingSessionFactory(),
            new HeadlessSubagentJsonCodec()
        );

        HeadlessSubagentOutput output = runner.execute(input());

        assertThat(output.status()).isEqualTo(SubagentRunStatus.FAILED);
        assertThat(output.runId()).isEqualTo("run_1");
        assertThat(output.errorMessage()).contains("Child turn ended with FAILED: permission denied");
    }

    @Test
    void runWritesOnlyStructuredJson() {
        HeadlessSubagentJsonCodec codec = new HeadlessSubagentJsonCodec();
        HeadlessSubagentRunner runner = new HeadlessSubagentRunner(
            new CapturingCoreFactory(TurnStatus.COMPLETED, "done"),
            new CapturingSessionFactory(),
            codec
        );
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        codec.writeInput(input(), request);
        ByteArrayOutputStream response = new ByteArrayOutputStream();

        runner.run(new ByteArrayInputStream(request.toByteArray()), response);

        String stdout = response.toString(StandardCharsets.UTF_8);
        assertThat(stdout.trim()).startsWith("{").endsWith("}");
        assertThat(codec.readOutput(new ByteArrayInputStream(response.toByteArray())).content()).isEqualTo("done");
    }

    @Test
    void rejectsMissingRunIdentity() {
        HeadlessSubagentInput invalid = new HeadlessSubagentInput(
            "inspect-session", "agent_1", "ses_child", "", "ses_parent", "entry_spawn", "inspect",
            Path.of("/tmp/project"), Path.of("/tmp/project"), SubagentToolPolicy.empty(),
            PermissionRuntimeState.forMode(PermissionMode.AUTO), 30
        );
        HeadlessSubagentRunner runner = new HeadlessSubagentRunner(
            new CapturingCoreFactory(TurnStatus.COMPLETED, "done"),
            new CapturingSessionFactory(),
            new HeadlessSubagentJsonCodec()
        );

        assertThatThrownBy(() -> runner.execute(invalid))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("runId is required");
    }

    private HeadlessSubagentInput input() {
        return new HeadlessSubagentInput(
            "inspect-session",
            "agent_1",
            "ses_child",
            "run_1",
            "ses_parent",
            "entry_spawn",
            "inspect session",
            Path.of("/tmp/sessions"),
            Path.of("/tmp/project"),
            new SubagentToolPolicy(List.of(), List.of("read", "grep", "glob")),
            PermissionRuntimeState.forMode(PermissionMode.AUTO),
            30
        );
    }

    private static final class CapturingCoreFactory implements AgentCoreFactoryPort {
        private final TurnStatus status;
        private final String text;
        private Path cwd;
        private SubagentToolPolicy policy;
        private TurnRequest request;

        private CapturingCoreFactory(TurnStatus status, String text) {
            this.status = status;
            this.text = text;
        }

        @Override
        public AgentCorePort create(Path cwd, SessionManagerPort sessionManager) {
            return create(cwd, sessionManager, SubagentToolPolicy.empty());
        }

        @Override
        public AgentCorePort create(Path cwd, SessionManagerPort sessionManager, SubagentToolPolicy policy) {
            this.cwd = cwd;
            this.policy = policy;
            return request -> {
                this.request = request;
                AgentMessage message = new AgentMessage(
                    "msg_final",
                    MessageRole.ASSISTANT,
                    MessageKind.TEXT,
                    List.<ContentBlock>of(new TextContentBlock(text, Map.of())),
                    Instant.EPOCH,
                    Optional.empty(),
                    Optional.empty()
                );
                return new TurnState("turn_1", request.sessionId(), null, List.of(message), 0, status);
            };
        }
    }

    private static final class CapturingSessionFactory implements SessionManagerFactoryPort {
        @Override
        public SessionManagerPort open(Path cwd, String sessionId) {
            return new MinimalSession(sessionId);
        }
    }

    private record MinimalSession(String sessionId) implements SessionManagerPort {
        @Override public SessionHandle openOrCreate(String sessionId) { return null; }
        @Override public SessionHandle append(SessionEntry entry) { return null; }
        @Override public SessionHandle switchLeaf(String leafId) { return null; }
        @Override public List<SessionEntry> branch(String leafId) { return List.of(); }
        @Override public SessionView currentView() { return new SessionView(sessionId, "entry_final"); }
        @Override public SessionView view(String leafId) { return currentView(); }
        @Override public List<AgentMessage> transcript(String leafId) { return List.of(); }
        @Override public SessionContext context(String leafId) {
            return new SessionContext(List.of(), List.of(), List.of(), null, null, null, PermissionMode.AUTO);
        }
        @Override public SessionHandle appendMessage(AgentMessage message) { return null; }
        @Override public SessionHandle fork(ForkRequest request) { return null; }
    }
}
