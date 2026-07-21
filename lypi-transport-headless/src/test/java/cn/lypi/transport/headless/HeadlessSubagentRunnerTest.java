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
import cn.lypi.contracts.skill.SkillMention;
import cn.lypi.contracts.session.ForkRequest;
import cn.lypi.contracts.session.SessionContext;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.session.SessionView;
import cn.lypi.contracts.subagent.HeadlessSubagentOutput;
import cn.lypi.contracts.subagent.HeadlessSubagentRunMode;
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
    void runReadsJsonExecutesChildTurnAndWritesJsonOutput() {
        CapturingAgentCoreFactory agentCoreFactory = new CapturingAgentCoreFactory(TurnStatus.COMPLETED, "child final answer");
        CapturingSessionFactory sessionFactory = new CapturingSessionFactory("entry_final");
        HeadlessSubagentJsonCodec codec = new HeadlessSubagentJsonCodec();
        HeadlessSubagentRunner runner = new HeadlessSubagentRunner(agentCoreFactory, sessionFactory, codec);
        String json = """
            {
              "childSessionId": "ses_child",
              "parentSessionId": "ses_parent",
              "parentSpawnEntryId": "entry_spawn",
              "prompt": "请审查代码",
              "sessionCwd": "/tmp/project/.lypi-store",
              "cwd": "/tmp/project/work",
              "allowedTools": [],
              "permissionMode": "DEFAULT_EXECUTE",
              "timeoutSeconds": 30
            }
            """;
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        runner.run(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), out);
        HeadlessSubagentOutput output = codec.readOutput(new ByteArrayInputStream(out.toByteArray()));

        assertThat(sessionFactory.openedCwd).isEqualTo(Path.of("/tmp/project/.lypi-store"));
        assertThat(sessionFactory.openedSessionId).isEqualTo("ses_child");
        assertThat(agentCoreFactory.createdCwd).isEqualTo(Path.of("/tmp/project/work"));
        assertThat(agentCoreFactory.createdSessionManager).isSameAs(sessionFactory.openedSessionManager);
        assertThat(agentCoreFactory.agentCore.request.sessionId()).isEqualTo("ses_child");
        assertThat(agentCoreFactory.agentCore.request.userInput()).isEqualTo("请审查代码");
        assertThat(output.childSessionId()).isEqualTo("ses_child");
        assertThat(output.status()).isEqualTo(SubagentRunStatus.SUCCEEDED);
        assertThat(output.summary()).isEqualTo("child final answer");
        assertThat(output.finalEntryId()).contains("entry_final");
        assertThat(output.finalEntryId()).hasValueSatisfying(id -> assertThat(id).doesNotContain("msg_final"));
    }

    @Test
    void runWritesStructuredFailureForInvalidInput() {
        HeadlessSubagentJsonCodec codec = new HeadlessSubagentJsonCodec();
        HeadlessSubagentRunner runner = new HeadlessSubagentRunner(
            new CapturingAgentCoreFactory(TurnStatus.COMPLETED, "unused"),
            new CapturingSessionFactory("entry_final"),
            codec
        );
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        runner.run(new ByteArrayInputStream("not-json".getBytes(StandardCharsets.UTF_8)), out);
        HeadlessSubagentOutput output = codec.readOutput(new ByteArrayInputStream(out.toByteArray()));

        assertThat(output.status()).isEqualTo(SubagentRunStatus.FAILED);
        assertThat(output.errorMessage()).isPresent();
    }

    @Test
    void failedChildTurnCarriesLastSummaryInErrorMessage() {
        HeadlessSubagentRunner runner = new HeadlessSubagentRunner(
            new CapturingAgentCoreFactory(TurnStatus.FAILED, "权限请求未获允许"),
            new CapturingSessionFactory("entry_final"),
            new HeadlessSubagentJsonCodec()
        );

        HeadlessSubagentOutput output = runner.execute(input("请调查架构"));

        assertThat(output.status()).isEqualTo(SubagentRunStatus.FAILED);
        assertThat(output.summary()).isEqualTo("权限请求未获允许");
        assertThat(output.errorMessage()).hasValue("Child turn ended with FAILED: 权限请求未获允许");
    }

    @Test
    void executeOpensChildSessionContextWithCanonicalPermissionRuntimeState() {
        PermissionRuntimeState runtimeState = customPermissionRuntimeState();
        CapturingAgentCoreFactory agentCoreFactory = new CapturingAgentCoreFactory(TurnStatus.COMPLETED, "权限上下文已读取");
        CapturingSessionFactory sessionFactory = new CapturingSessionFactory("entry_final", runtimeState);
        HeadlessSubagentRunner runner = new HeadlessSubagentRunner(agentCoreFactory, sessionFactory, new HeadlessSubagentJsonCodec());

        HeadlessSubagentOutput output = runner.execute(new cn.lypi.contracts.subagent.HeadlessSubagentInput(
            "ses_child",
            "ses_parent",
            "entry_spawn",
            "验证权限上下文",
            Path.of("/tmp/project/.lypi-store"),
            Path.of("/tmp/project/work"),
            List.of(),
            new SubagentToolPolicy(List.of(), List.of()),
            runtimeState,
            30,
            HeadlessSubagentRunMode.START,
            List.of()
        ));

        assertThat(agentCoreFactory.agentCore.observedPermissionRuntimeState).isEqualTo(runtimeState);
        assertThat(output.status()).isEqualTo(SubagentRunStatus.SUCCEEDED);
    }

    @Test
    void runWritesPureJsonStructuredFailureWhenChildPermissionApprovalFails() {
        HeadlessSubagentJsonCodec codec = new HeadlessSubagentJsonCodec();
        HeadlessSubagentRunner runner = new HeadlessSubagentRunner(
            new CapturingAgentCoreFactory(TurnStatus.FAILED, "权限请求未获允许"),
            new CapturingSessionFactory("entry_final"),
            codec
        );
        String json = """
            {
              "childSessionId": "ses_child",
              "parentSessionId": "ses_parent",
              "parentSpawnEntryId": "entry_spawn",
              "prompt": "请运行需要审批的命令",
              "sessionCwd": "/tmp/project/.lypi-store",
              "cwd": "/tmp/project/work",
              "allowedTools": ["bash"],
              "permissionRuntimeState": {
                "approvalPolicy": {
                  "mode": "ON_REQUEST"
                },
                "activePermissionProfile": {
                  "id": ":workspace"
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

        runner.run(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), out);
        String stdout = out.toString(StandardCharsets.UTF_8);
        HeadlessSubagentOutput output = codec.readOutput(new ByteArrayInputStream(out.toByteArray()));

        assertThat(stdout.trim()).startsWith("{").endsWith("}");
        assertThat(stdout).doesNotContain("Started LyPiApplication");
        assertThat(output.status()).isEqualTo(SubagentRunStatus.FAILED);
        assertThat(output.summary()).isEqualTo("权限请求未获允许");
        assertThat(output.errorMessage()).hasValue("Child turn ended with FAILED: 权限请求未获允许");
    }

    @Test
    void continueModeUsesCurrentLeafAsTurnParentEntryId() {
        CapturingAgentCoreFactory agentCoreFactory = new CapturingAgentCoreFactory(TurnStatus.COMPLETED, "继续后的结果");
        CapturingSessionFactory sessionFactory = new CapturingSessionFactory("entry_previous_leaf");
        HeadlessSubagentRunner runner = new HeadlessSubagentRunner(agentCoreFactory, sessionFactory, new HeadlessSubagentJsonCodec());

        HeadlessSubagentOutput output = runner.execute(new cn.lypi.contracts.subagent.HeadlessSubagentInput(
            "ses_child",
            "ses_parent",
            "entry_spawn",
            "继续执行",
            Path.of("/tmp/project/.lypi-store"),
            Path.of("/tmp/project/work"),
            new SubagentToolPolicy(List.of(), List.of()),
            PermissionMode.ASK,
            30,
            HeadlessSubagentRunMode.CONTINUE
        ));

        assertThat(agentCoreFactory.agentCore.request.parentEntryId()).contains("entry_previous_leaf");
        assertThat(output.status()).isEqualTo(SubagentRunStatus.SUCCEEDED);
    }

    @Test
    void passesToolPolicyToAgentCoreFactoryForChildToolFiltering() {
        CapturingAgentCoreFactory agentCoreFactory = new CapturingAgentCoreFactory(TurnStatus.COMPLETED, "工具策略已透传");
        CapturingSessionFactory sessionFactory = new CapturingSessionFactory("entry_final");
        HeadlessSubagentRunner runner = new HeadlessSubagentRunner(agentCoreFactory, sessionFactory, new HeadlessSubagentJsonCodec());
        SubagentToolPolicy toolPolicy = new SubagentToolPolicy(
            List.of("read", "bash"),
            List.of("read", "grep", "glob", "bash")
        );

        HeadlessSubagentOutput output = runner.execute(new cn.lypi.contracts.subagent.HeadlessSubagentInput(
            "ses_child",
            "ses_parent",
            "entry_spawn",
            "验证工具策略",
            Path.of("/tmp/project/.lypi-store"),
            Path.of("/tmp/project/work"),
            toolPolicy,
            PermissionMode.AUTO,
            30,
            HeadlessSubagentRunMode.START
        ));

        assertThat(agentCoreFactory.createdToolPolicy).isEqualTo(toolPolicy);
        assertThat(output.status()).isEqualTo(SubagentRunStatus.SUCCEEDED);
    }

    @Test
    void passesSkillMentionsToChildTurnRequest() {
        CapturingAgentCoreFactory agentCoreFactory = new CapturingAgentCoreFactory(TurnStatus.COMPLETED, "skill 已注入");
        CapturingSessionFactory sessionFactory = new CapturingSessionFactory("entry_final");
        HeadlessSubagentRunner runner = new HeadlessSubagentRunner(agentCoreFactory, sessionFactory, new HeadlessSubagentJsonCodec());
        SkillMention skill = new SkillMention("doc", Path.of("/tmp/project/.ly-pi/skills/doc/SKILL.md"));

        HeadlessSubagentOutput output = runner.execute(new cn.lypi.contracts.subagent.HeadlessSubagentInput(
            "ses_child",
            "ses_parent",
            "entry_spawn",
            "使用 $doc",
            Path.of("/tmp/project/.lypi-store"),
            Path.of("/tmp/project/work"),
            new SubagentToolPolicy(List.of(), List.of()),
            PermissionMode.ASK,
            30,
            HeadlessSubagentRunMode.START,
            List.of(skill)
        ));

        assertThat(agentCoreFactory.agentCore.request.skillMentions()).containsExactly(skill);
        assertThat(output.status()).isEqualTo(SubagentRunStatus.SUCCEEDED);
    }

    private cn.lypi.contracts.subagent.HeadlessSubagentInput input(String prompt) {
        return new cn.lypi.contracts.subagent.HeadlessSubagentInput(
            "ses_child",
            "ses_parent",
            "entry_spawn",
            prompt,
            Path.of("/tmp/project/.lypi-store"),
            Path.of("/tmp/project/work"),
            new SubagentToolPolicy(List.of(), List.of()),
            PermissionMode.ASK,
            30,
            null
        );
    }

    private PermissionRuntimeState customPermissionRuntimeState() {
        return new PermissionRuntimeState(
            new ApprovalPolicy(ApprovalMode.UNLESS_TRUSTED),
            new ActivePermissionProfile(":workspace-write"),
            cn.lypi.contracts.security.PermissionProfiles.workspace(),
            new LegacyPermissionBehavior(false, false, false),
            PermissionMode.ASK
        );
    }

    private static final class CapturingAgentCoreFactory implements AgentCoreFactoryPort {
        private final CapturingAgentCore agentCore;
        private Path createdCwd;
        private SessionManagerPort createdSessionManager;
        private SubagentToolPolicy createdToolPolicy;

        private CapturingAgentCoreFactory(TurnStatus status, String finalText) {
            this.agentCore = new CapturingAgentCore(status, finalText);
        }

        @Override
        public AgentCorePort create(Path cwd, SessionManagerPort sessionManager) {
            this.createdCwd = cwd;
            this.createdSessionManager = sessionManager;
            agentCore.observedPermissionRuntimeState = sessionManager
                .context(sessionManager.currentView().leafId())
                .permissionRuntimeState();
            return agentCore;
        }

        @Override
        public AgentCorePort create(Path cwd, SessionManagerPort sessionManager, SubagentToolPolicy toolPolicy) {
            this.createdCwd = cwd;
            this.createdSessionManager = sessionManager;
            this.createdToolPolicy = toolPolicy;
            agentCore.observedPermissionRuntimeState = sessionManager
                .context(sessionManager.currentView().leafId())
                .permissionRuntimeState();
            return agentCore;
        }
    }

    private static final class CapturingAgentCore implements AgentCorePort {
        private final TurnStatus status;
        private final String finalText;
        private TurnRequest request;
        private PermissionRuntimeState observedPermissionRuntimeState;

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
        private final PermissionRuntimeState permissionRuntimeState;
        private Path openedCwd;
        private String openedSessionId;
        private SessionManagerPort openedSessionManager;

        private CapturingSessionFactory(String leafId) {
            this(leafId, PermissionRuntimeState.fromLegacy(PermissionMode.ASK));
        }

        private CapturingSessionFactory(String leafId, PermissionRuntimeState permissionRuntimeState) {
            this.leafId = leafId;
            this.permissionRuntimeState = permissionRuntimeState;
        }

        @Override
        public SessionManagerPort open(Path cwd, String sessionId) {
            this.openedCwd = cwd;
            this.openedSessionId = sessionId;
            this.openedSessionManager = new MinimalSessionManager(sessionId, leafId, permissionRuntimeState);
            return openedSessionManager;
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
            return new SessionContext(
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null,
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
}
