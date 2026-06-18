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
import cn.lypi.contracts.runtime.AgentCoreFactoryPort;
import cn.lypi.contracts.runtime.AgentCorePort;
import cn.lypi.contracts.runtime.SessionManagerFactoryPort;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.security.ActivePermissionProfile;
import cn.lypi.contracts.security.ApprovalMode;
import cn.lypi.contracts.security.ApprovalPolicy;
import cn.lypi.contracts.security.LegacyPermissionBehavior;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.session.ForkRequest;
import cn.lypi.contracts.session.SessionContext;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.session.SessionView;
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

class PermissionRuntimeHeadlessEndToEndTest {
    @Test
    void runConsumesCanonicalJsonAndReturnsStructuredApprovalFailure() {
        HeadlessSubagentJsonCodec codec = new HeadlessSubagentJsonCodec();
        CapturingAgentCoreFactory agentCoreFactory = new CapturingAgentCoreFactory(TurnStatus.FAILED, "approval denied");
        HeadlessSubagentRunner runner = new HeadlessSubagentRunner(
            agentCoreFactory,
            new CapturingSessionFactory("entry_leaf", customPermissionRuntimeState()),
            codec
        );
        String json = """
            {
              "childSessionId": "ses_child",
              "parentSessionId": "ses_parent",
              "parentSpawnEntryId": "entry_spawn",
              "prompt": "run command",
              "sessionCwd": "/tmp/project/.ly-pi",
              "cwd": "/tmp/project",
              "toolPolicy": {
                "requestedTools": ["bash"],
                "effectiveTools": ["bash"]
              },
              "permissionRuntimeState": {
                "approvalPolicy": {
                  "mode": "UNLESS_TRUSTED"
                },
                "activePermissionProfile": {
                  "id": ":workspace-write"
                },
                "legacyBehavior": {
                  "defaultBashRequiresEscalation": false,
                  "allowExplicitEscalationWithoutPrompt": false,
                  "hardSafetyEnabled": true
                },
                "legacyPermissionMode": "DEFAULT_EXECUTE"
              },
              "timeoutSeconds": 30
            }
            """;
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        assertThat(codec.readInput(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))).permissionRuntimeState())
            .isEqualTo(customPermissionRuntimeState());
        runner.run(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), out);
        HeadlessSubagentOutput output = codec.readOutput(new ByteArrayInputStream(out.toByteArray()));

        assertThat(agentCoreFactory.createdToolPolicy)
            .isEqualTo(new SubagentToolPolicy(List.of("bash"), List.of("bash")));
        assertThat(agentCoreFactory.agentCore.observedPermissionRuntimeState).isEqualTo(customPermissionRuntimeState());
        assertThat(output.status()).isEqualTo(SubagentRunStatus.FAILED);
        assertThat(output.summary()).isEqualTo("approval denied");
        assertThat(output.errorMessage()).hasValue("Child turn ended with FAILED: approval denied");
        assertThat(out.toString(StandardCharsets.UTF_8).trim()).startsWith("{").endsWith("}");
    }

    @Test
    void runAcceptsLegacyPermissionModeAsCanonicalRuntimeStateForApprovedChildTurn() {
        HeadlessSubagentJsonCodec codec = new HeadlessSubagentJsonCodec();
        CapturingAgentCoreFactory agentCoreFactory = new CapturingAgentCoreFactory(TurnStatus.COMPLETED, "done");
        HeadlessSubagentRunner runner = new HeadlessSubagentRunner(
            agentCoreFactory,
            new CapturingSessionFactory("entry_final", PermissionRuntimeState.fromLegacy(PermissionMode.BYPASS)),
            codec
        );
        String json = """
            {
              "childSessionId": "ses_child",
              "parentSessionId": "ses_parent",
              "parentSpawnEntryId": "entry_spawn",
              "prompt": "finish work",
              "sessionCwd": "/tmp/project/.ly-pi",
              "cwd": "/tmp/project",
              "allowedTools": [],
              "permissionMode": "BYPASS",
              "timeoutSeconds": 30
            }
            """;
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        runner.run(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), out);
        HeadlessSubagentOutput output = codec.readOutput(new ByteArrayInputStream(out.toByteArray()));

        assertThat(agentCoreFactory.agentCore.observedPermissionRuntimeState)
            .isEqualTo(PermissionRuntimeState.fromLegacy(PermissionMode.BYPASS));
        assertThat(output.status()).isEqualTo(SubagentRunStatus.SUCCEEDED);
        assertThat(output.summary()).isEqualTo("done");
        assertThat(output.finalEntryId()).contains("entry_final");
    }

    private static PermissionRuntimeState customPermissionRuntimeState() {
        return new PermissionRuntimeState(
            new ApprovalPolicy(ApprovalMode.UNLESS_TRUSTED),
            new ActivePermissionProfile(":workspace-write"),
            new LegacyPermissionBehavior(false, false, true),
            PermissionMode.DEFAULT_EXECUTE
        );
    }

    private static final class CapturingAgentCoreFactory implements AgentCoreFactoryPort {
        private final CapturingAgentCore agentCore;
        private SubagentToolPolicy createdToolPolicy;

        private CapturingAgentCoreFactory(TurnStatus status, String finalText) {
            this.agentCore = new CapturingAgentCore(status, finalText);
        }

        @Override
        public AgentCorePort create(Path cwd, SessionManagerPort sessionManager) {
            agentCore.observedPermissionRuntimeState = sessionManager
                .context(sessionManager.currentView().leafId())
                .permissionRuntimeState();
            return agentCore;
        }

        @Override
        public AgentCorePort create(Path cwd, SessionManagerPort sessionManager, SubagentToolPolicy toolPolicy) {
            this.createdToolPolicy = toolPolicy;
            return create(cwd, sessionManager);
        }
    }

    private static final class CapturingAgentCore implements AgentCorePort {
        private final TurnStatus status;
        private final String finalText;
        private PermissionRuntimeState observedPermissionRuntimeState;

        private CapturingAgentCore(TurnStatus status, String finalText) {
            this.status = status;
            this.finalText = finalText;
        }

        @Override
        public TurnState execute(TurnRequest request) {
            AgentMessage message = new AgentMessage(
                "msg_final",
                MessageRole.ASSISTANT,
                MessageKind.TEXT,
                List.<ContentBlock>of(new TextContentBlock(finalText, Map.of())),
                Instant.parse("2026-06-18T00:00:00Z"),
                Optional.empty(),
                Optional.empty()
            );
            return new TurnState("turn_child", request.sessionId(), null, List.of(message), 0, status);
        }
    }

    private static final class CapturingSessionFactory implements SessionManagerFactoryPort {
        private final String leafId;
        private final PermissionRuntimeState permissionRuntimeState;

        private CapturingSessionFactory(String leafId, PermissionRuntimeState permissionRuntimeState) {
            this.leafId = leafId;
            this.permissionRuntimeState = permissionRuntimeState;
        }

        @Override
        public SessionManagerPort open(Path cwd, String sessionId) {
            return new MinimalSessionManager(sessionId, leafId, permissionRuntimeState);
        }
    }

    private record MinimalSessionManager(
        String sessionId,
        String leafId,
        PermissionRuntimeState permissionRuntimeState
    ) implements SessionManagerPort {
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
            return new SessionContext(List.of(), List.of(), List.of(), null, null, null, permissionRuntimeState);
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
