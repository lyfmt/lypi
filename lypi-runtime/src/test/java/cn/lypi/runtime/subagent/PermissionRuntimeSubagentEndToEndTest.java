package cn.lypi.runtime.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.runtime.ChildSessionPort;
import cn.lypi.contracts.runtime.SessionManagerFactoryPort;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.security.ActivePermissionProfile;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.ApprovalMode;
import cn.lypi.contracts.security.ApprovalPolicy;
import cn.lypi.contracts.security.LegacyPermissionBehavior;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.session.ChildSessionRequest;
import cn.lypi.contracts.session.ForkRequest;
import cn.lypi.contracts.session.PermissionRuntimeStateChangeEntry;
import cn.lypi.contracts.session.SessionContext;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.session.SessionView;
import cn.lypi.contracts.subagent.HeadlessSubagentOutput;
import cn.lypi.contracts.subagent.HeadlessSubagentInput;
import cn.lypi.contracts.subagent.SubagentContinueRequest;
import cn.lypi.contracts.subagent.SubagentRunStatus;
import cn.lypi.contracts.subagent.SubagentSpawnRequest;
import cn.lypi.contracts.subagent.SubagentSpawnResult;
import cn.lypi.contracts.subagent.SubagentToolPolicy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PermissionRuntimeSubagentEndToEndTest {
    private static final Instant NOW = Instant.parse("2026-06-18T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void spawnAndContinueCarryCanonicalRuntimeStateAcrossChildProtocol() {
        CapturingChildSessions childSessions = new CapturingChildSessions();
        CapturingSession parentSession = new CapturingSession("ses_parent", "entry_parent", customPermissionRuntimeState());
        CapturingSession childSession = new CapturingSession(
            "ses_child",
            "entry_child_leaf",
            PermissionRuntimeState.fromLegacy(PermissionMode.AUTO)
        );
        CompletingProcessRunner processRunner = new CompletingProcessRunner();
        DefaultAgentCenter center = center(childSessions, parentSession, childSession, processRunner);

        SubagentSpawnResult spawned = center.spawn(new SubagentSpawnRequest(
            "ses_parent",
            "entry_parent",
            "first run",
            tempDir,
            List.of(),
            SubagentToolPolicy.empty(),
            PermissionRuntimeState.fromLegacy(PermissionMode.ASK),
            30,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            false
        ));
        processRunner.complete(new HeadlessSubagentOutput(
            spawned.childSessionId(),
            SubagentRunStatus.SUCCEEDED,
            "first done",
            Optional.of("entry_child_leaf"),
            Optional.empty()
        ));

        assertThat(childSessions.request.permissionRuntimeState()).contains(customPermissionRuntimeState());
        assertThat(processRunner.input.permissionRuntimeState()).isEqualTo(customPermissionRuntimeState());

        PermissionRuntimeState override = PermissionRuntimeState.fromLegacy(PermissionMode.BYPASS);
        center.continueRun(new SubagentContinueRequest(
            "ses_parent",
            "entry_continue",
            spawned.childSessionId(),
            "second run",
            tempDir,
            List.of(),
            SubagentToolPolicy.empty(),
            override,
            30,
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        ));

        assertThat(processRunner.input.permissionRuntimeState()).isEqualTo(override);
        assertThat(childSession.entries)
            .singleElement()
            .isInstanceOfSatisfying(PermissionRuntimeStateChangeEntry.class, entry -> {
                assertThat(entry.parentId()).isEqualTo("entry_child_leaf");
                assertThat(entry.permissionRuntimeState()).isEqualTo(override);
            });
    }

    @Test
    void continueWithoutPermissionOverrideUsesChildCurrentRuntimeState() {
        CapturingChildSessions childSessions = new CapturingChildSessions();
        CapturingSession parentSession = new CapturingSession("ses_parent", "entry_parent", customPermissionRuntimeState());
        PermissionRuntimeState childRuntimeState = PermissionRuntimeState.fromLegacy(PermissionMode.AUTO);
        CapturingSession childSession = new CapturingSession("ses_child", "entry_child_leaf", childRuntimeState);
        CompletingProcessRunner processRunner = new CompletingProcessRunner();
        DefaultAgentCenter center = center(childSessions, parentSession, childSession, processRunner);

        SubagentSpawnResult spawned = center.spawn(new SubagentSpawnRequest(
            "ses_parent",
            "entry_parent",
            "first run",
            tempDir,
            List.of(),
            SubagentToolPolicy.empty(),
            PermissionRuntimeState.fromLegacy(PermissionMode.ASK),
            30,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            false
        ));
        processRunner.complete(new HeadlessSubagentOutput(
            spawned.childSessionId(),
            SubagentRunStatus.SUCCEEDED,
            "first done",
            Optional.of("entry_child_leaf"),
            Optional.empty()
        ));

        center.continueRun(new SubagentContinueRequest(
            "ses_parent",
            "entry_continue",
            spawned.childSessionId(),
            "second run",
            tempDir,
            List.of(),
            30
        ));

        assertThat(processRunner.input.permissionRuntimeState()).isEqualTo(childRuntimeState);
        assertThat(childSession.entries).isEmpty();
    }

    private DefaultAgentCenter center(
        CapturingChildSessions childSessions,
        CapturingSession parentSession,
        CapturingSession childSession,
        CompletingProcessRunner processRunner
    ) {
        DefaultMailboxService mailbox = new DefaultMailboxService(
            new JsonlMailboxStore(tempDir),
            parentSession,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        return new DefaultAgentCenter(
            List.of("lypi", "headless-subagent"),
            childSessions,
            parentSession,
            tempDir,
            sessionFactory(parentSession, childSession),
            processRunner,
            mailbox,
            new MailboxDeliveryService(mailbox, ignored -> false),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private SessionManagerFactoryPort sessionFactory(CapturingSession parentSession, CapturingSession childSession) {
        return (cwd, sessionId) -> "ses_parent".equals(sessionId) ? parentSession : childSession;
    }

    private static PermissionRuntimeState customPermissionRuntimeState() {
        return new PermissionRuntimeState(
            new ApprovalPolicy(ApprovalMode.UNLESS_TRUSTED),
            new ActivePermissionProfile(":workspace-write"),
            cn.lypi.contracts.security.PermissionProfiles.workspace(),
            new LegacyPermissionBehavior(false, false, true),
            PermissionMode.ASK
        );
    }

    private static final class CapturingChildSessions implements ChildSessionPort {
        private ChildSessionRequest request;

        @Override
        public SessionHandle create(ChildSessionRequest request) {
            this.request = request;
            return new SessionHandle(request.childSessionId(), request.cwd().resolve("child.jsonl"), null, Map.of());
        }
    }

    private static final class CompletingProcessRunner implements SubagentProcessRunner {
        private HeadlessSubagentInput input;
        private CompletableFuture<HeadlessSubagentOutput> completion;

        @Override
        public SubagentProcessHandle start(HeadlessSubagentInput input) {
            this.input = input;
            this.completion = new CompletableFuture<>();
            return new SubagentProcessHandle() {
                @Override
                public CompletableFuture<HeadlessSubagentOutput> completion() {
                    return completion;
                }

                @Override
                public void interrupt() {
                }
            };
        }

        private void complete(HeadlessSubagentOutput output) {
            completion.complete(output);
        }
    }

    private static final class CapturingSession implements SessionManagerPort {
        private final String sessionId;
        private String leafId;
        private PermissionRuntimeState permissionRuntimeState;
        private final List<SessionEntry> entries = new ArrayList<>();

        private CapturingSession(String sessionId, String leafId, PermissionRuntimeState permissionRuntimeState) {
            this.sessionId = sessionId;
            this.leafId = leafId;
            this.permissionRuntimeState = permissionRuntimeState;
        }

        @Override
        public SessionHandle openOrCreate(String sessionId) {
            return new SessionHandle(sessionId, Path.of(sessionId + ".jsonl"), leafId, Map.of());
        }

        @Override
        public SessionHandle append(SessionEntry entry) {
            entries.add(entry);
            leafId = entry.id();
            if (entry instanceof PermissionRuntimeStateChangeEntry permissionRuntimeChange) {
                permissionRuntimeState = permissionRuntimeChange.permissionRuntimeState();
            }
            return new SessionHandle(sessionId, Path.of(sessionId + ".jsonl"), entry.id(), Map.of(entry.id(), entry));
        }

        @Override
        public SessionHandle switchLeaf(String leafId) {
            return new SessionHandle(sessionId, Path.of(sessionId + ".jsonl"), leafId, Map.of());
        }

        @Override
        public List<SessionEntry> branch(String leafId) {
            return List.of();
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
}
