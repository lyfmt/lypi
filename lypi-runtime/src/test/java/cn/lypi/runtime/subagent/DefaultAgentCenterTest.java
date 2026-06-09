package cn.lypi.runtime.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.runtime.ChildSessionPort;
import cn.lypi.contracts.runtime.SessionManagerFactoryPort;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.session.AgentLifecycleEntry;
import cn.lypi.contracts.session.ChildSessionRequest;
import cn.lypi.contracts.session.ForkRequest;
import cn.lypi.contracts.session.SessionContext;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.session.SessionView;
import cn.lypi.contracts.subagent.HeadlessSubagentInput;
import cn.lypi.contracts.subagent.HeadlessSubagentOutput;
import cn.lypi.contracts.subagent.MailboxMessage;
import cn.lypi.contracts.subagent.MailboxStatus;
import cn.lypi.contracts.subagent.SubagentRunStatus;
import cn.lypi.contracts.subagent.SubagentSpawnRequest;
import cn.lypi.contracts.subagent.SubagentSpawnResult;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultAgentCenterTest {
    private static final Instant NOW = Instant.parse("2026-06-09T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void spawnCreatesChildSessionLifecycleEntryAndPendingMailboxOnCompletion() {
        CapturingChildSessions childSessions = new CapturingChildSessions();
        CapturingParentSession parentSession = new CapturingParentSession("ses_parent", "entry_parent");
        CompletingProcessRunner processRunner = new CompletingProcessRunner();
        DefaultMailboxService mailbox = new DefaultMailboxService(
            new JsonlMailboxStore(tempDir),
            parentSession,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        DefaultAgentCenter center = new DefaultAgentCenter(
            List.of("lypi", "headless-subagent"),
            childSessions,
            parentSession,
            tempDir,
            sessionFactory(parentSession),
            processRunner,
            mailbox,
            new MailboxDeliveryService(mailbox, ignored -> false),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );

        SubagentSpawnResult result = center.spawn(new SubagentSpawnRequest(
            "ses_parent",
            "entry_parent",
            "请审查代码",
            tempDir,
            List.of(),
            PermissionMode.DEFAULT_EXECUTE,
            30,
            Optional.of("reviewer"),
            Optional.of("code-review")
        ));

        assertThat(result.status()).isEqualTo(SubagentRunStatus.STARTED);
        assertThat(childSessions.request.parentSessionId()).isEqualTo("ses_parent");
        assertThat(childSessions.request.parentSpawnEntryId()).isEqualTo(result.parentSpawnEntryId());
        assertThat(parentSession.entries)
            .singleElement()
            .isInstanceOfSatisfying(AgentLifecycleEntry.class, entry -> {
                assertThat(entry.agentId()).isEqualTo(result.agentId());
                assertThat(entry.childSessionId()).isEqualTo(result.childSessionId());
                assertThat(entry.parentSessionId()).isEqualTo("ses_parent");
                assertThat(entry.lifecycle()).isEqualTo("spawned");
            });
        assertThat(processRunner.input.childSessionId()).isEqualTo(result.childSessionId());
        assertThat(processRunner.input.prompt()).isEqualTo("请审查代码");

        processRunner.complete(new HeadlessSubagentOutput(
            result.childSessionId(),
            SubagentRunStatus.SUCCEEDED,
            "完成摘要",
            Optional.of("entry_final"),
            Optional.empty()
        ));

        assertThat(mailbox.read("ses_parent", Set.of(MailboxStatus.PENDING)))
            .singleElement()
            .satisfies(message -> {
                assertThat(message.agentId()).isEqualTo(result.agentId());
                assertThat(message.childSessionId()).isEqualTo(result.childSessionId());
                assertThat(message.parentSpawnEntryId()).isEqualTo(result.parentSpawnEntryId());
                assertThat(message.summary()).isEqualTo("完成摘要");
            });
        assertThat(parentSession.entries)
            .filteredOn(AgentLifecycleEntry.class::isInstance)
            .map(AgentLifecycleEntry.class::cast)
            .extracting(AgentLifecycleEntry::lifecycle)
            .containsExactly("spawned", "finished");
    }

    @Test
    void completionLifecycleDoesNotMoveParentSessionCurrentLeaf() {
        ChildSessionPort childSessions = request -> null;
        CapturingParentSession parentSession = new CapturingParentSession("ses_parent", "entry_parent");
        CapturingParentSession persistentParentSession = new CapturingParentSession("ses_parent", "entry_parent");
        String originalLeaf = parentSession.currentView().leafId();
        CompletingProcessRunner processRunner = new CompletingProcessRunner();
        DefaultMailboxService mailbox = new DefaultMailboxService(
            new JsonlMailboxStore(tempDir),
            parentSession,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        DefaultAgentCenter center = new DefaultAgentCenter(
            List.of("lypi", "headless-subagent"),
            childSessions,
            parentSession,
            tempDir,
            (cwd, sessionId) -> persistentParentSession,
            processRunner,
            mailbox,
            new MailboxDeliveryService(mailbox, ignored -> false),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        SubagentSpawnResult result = center.spawn(request("ses_parent", originalLeaf, "请审查代码"));
        parentSession.switchLeaf(originalLeaf);

        processRunner.complete(new HeadlessSubagentOutput(
            result.childSessionId(),
            SubagentRunStatus.SUCCEEDED,
            "完成摘要",
            Optional.of("entry_final"),
            Optional.empty()
        ));

        assertThat(parentSession.currentView().leafId()).isEqualTo(originalLeaf);
        assertThat(persistentParentSession.entries)
            .filteredOn(AgentLifecycleEntry.class::isInstance)
            .map(AgentLifecycleEntry.class::cast)
            .extracting(AgentLifecycleEntry::lifecycle)
            .containsExactly("finished");
    }

    @Test
    void missingSubagentCommandReturnsStructuredFailureWithoutCreatingPersistentState() {
        CapturingChildSessions childSessions = new CapturingChildSessions();
        CapturingParentSession parentSession = new CapturingParentSession("ses_parent", "entry_parent");
        CompletingProcessRunner processRunner = new CompletingProcessRunner();
        DefaultMailboxService mailbox = new DefaultMailboxService(
            new JsonlMailboxStore(tempDir),
            parentSession,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        DefaultAgentCenter center = new DefaultAgentCenter(
            List.of(),
            childSessions,
            parentSession,
            tempDir,
            sessionFactory(parentSession),
            processRunner,
            mailbox,
            new MailboxDeliveryService(mailbox, ignored -> false),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );

        SubagentSpawnResult result = center.spawn(new SubagentSpawnRequest(
            "ses_parent",
            "entry_parent",
            "请审查代码",
            tempDir,
            List.of(),
            PermissionMode.DEFAULT_EXECUTE,
            30,
            Optional.empty(),
            Optional.empty()
        ));

        assertThat(result.status()).isEqualTo(SubagentRunStatus.FAILED);
        assertThat(result.message()).hasValueSatisfying(message -> assertThat(message).contains("Subagent command is not configured"));
        assertThat(childSessions.request).isNull();
        assertThat(parentSession.entries).isEmpty();
        assertThat(processRunner.input).isNull();
        assertThat(mailbox.read("ses_parent", Set.of())).isEmpty();
    }

    @Test
    void interruptStopsRunningProcessAndCreatesInterruptedMailbox() {
        CapturingChildSessions childSessions = new CapturingChildSessions();
        CapturingParentSession parentSession = new CapturingParentSession("ses_parent", "entry_parent");
        CompletingProcessRunner processRunner = new CompletingProcessRunner();
        DefaultMailboxService mailbox = new DefaultMailboxService(
            new JsonlMailboxStore(tempDir),
            parentSession,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        DefaultAgentCenter center = new DefaultAgentCenter(
            List.of("lypi", "headless-subagent"),
            childSessions,
            parentSession,
            tempDir,
            sessionFactory(parentSession),
            processRunner,
            mailbox,
            new MailboxDeliveryService(mailbox, ignored -> false),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        SubagentSpawnResult result = center.spawn(new SubagentSpawnRequest(
            "ses_parent",
            "entry_parent",
            "请审查代码",
            tempDir,
            List.of(),
            PermissionMode.DEFAULT_EXECUTE,
            30,
            Optional.empty(),
            Optional.empty()
        ));

        center.interrupt(result.agentId());

        assertThat(processRunner.interrupted).isTrue();
        processRunner.complete(new HeadlessSubagentOutput(
            result.childSessionId(),
            SubagentRunStatus.INTERRUPTED,
            "已中断",
            Optional.empty(),
            Optional.of("interrupted")
        ));

        assertThat(mailbox.read("ses_parent", Set.of(MailboxStatus.PENDING)))
            .singleElement()
            .extracting(MailboxMessage::summary)
            .isEqualTo("已中断");
    }

    @Test
    void failedAndTimedOutRunsPublishReadableMailboxAndLifecycleStatus() {
        CapturingChildSessions childSessions = new CapturingChildSessions();
        CapturingParentSession parentSession = new CapturingParentSession("ses_parent", "entry_parent");
        CompletingProcessRunner processRunner = new CompletingProcessRunner();
        DefaultMailboxService mailbox = new DefaultMailboxService(
            new JsonlMailboxStore(tempDir),
            parentSession,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        DefaultAgentCenter center = new DefaultAgentCenter(
            List.of("lypi", "headless-subagent"),
            childSessions,
            parentSession,
            tempDir,
            sessionFactory(parentSession),
            processRunner,
            mailbox,
            new MailboxDeliveryService(mailbox, ignored -> false),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );

        SubagentSpawnResult failed = center.spawn(request("ses_parent", "entry_parent", "检查失败"));
        processRunner.complete(new HeadlessSubagentOutput(
            failed.childSessionId(),
            SubagentRunStatus.FAILED,
            "",
            Optional.empty(),
            Optional.of("模型调用失败")
        ));
        SubagentSpawnResult timedOut = center.spawn(request("ses_parent", "entry_parent", "检查超时"));
        processRunner.complete(new HeadlessSubagentOutput(
            timedOut.childSessionId(),
            SubagentRunStatus.TIMED_OUT,
            "",
            Optional.empty(),
            Optional.of("Subagent process timed out after 1 seconds")
        ));

        assertThat(mailbox.read("ses_parent", Set.of(MailboxStatus.PENDING)))
            .extracting(MailboxMessage::summary)
            .containsExactly("模型调用失败", "Subagent process timed out after 1 seconds");
        assertThat(parentSession.entries)
            .filteredOn(AgentLifecycleEntry.class::isInstance)
            .map(AgentLifecycleEntry.class::cast)
            .extracting(AgentLifecycleEntry::lifecycle)
            .containsExactly("spawned", "failed", "spawned", "timed_out");
    }

    @Test
    void readResultFallsBackToPersistedMailboxContentRef() {
        CapturingParentSession parentSession = new CapturingParentSession("ses_parent", "entry_parent");
        DefaultMailboxService mailbox = new DefaultMailboxService(
            new JsonlMailboxStore(tempDir),
            parentSession,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        mailbox.publish(new MailboxMessage(
            "mail_01",
            "agent_01",
            "ses_child",
            "ses_parent",
            "entry_spawn",
            "持久化摘要",
            new cn.lypi.contracts.subagent.SubagentResultRef("ses_child", "entry_final", Optional.empty()),
            MailboxStatus.PENDING,
            NOW,
            NOW
        ));
        DefaultAgentCenter center = new DefaultAgentCenter(
            List.of("lypi", "headless-subagent"),
            request -> null,
            parentSession,
            tempDir,
            sessionFactory(parentSession),
            new CompletingProcessRunner(),
            mailbox,
            new MailboxDeliveryService(mailbox, ignored -> false),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );

        Optional<HeadlessSubagentOutput> result = center.readResult("ses_child");

        assertThat(result).hasValueSatisfying(output -> {
            assertThat(output.childSessionId()).isEqualTo("ses_child");
            assertThat(output.summary()).isEqualTo("持久化摘要");
            assertThat(output.finalEntryId()).hasValue("entry_final");
        });
    }

    @Test
    void completionLifecycleUsesParentCwdWhenChildCwdIsOverridden() {
        CapturingChildSessions childSessions = new CapturingChildSessions();
        CapturingParentSession parentSession = new CapturingParentSession("ses_parent", "entry_parent");
        CompletingProcessRunner processRunner = new CompletingProcessRunner();
        CapturingSessionFactory sessionFactory = new CapturingSessionFactory(parentSession);
        DefaultMailboxService mailbox = new DefaultMailboxService(
            new JsonlMailboxStore(tempDir),
            parentSession,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        DefaultAgentCenter center = new DefaultAgentCenter(
            List.of("lypi", "headless-subagent"),
            childSessions,
            parentSession,
            tempDir,
            sessionFactory,
            processRunner,
            mailbox,
            new MailboxDeliveryService(mailbox, ignored -> false),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        Path childCwd = tempDir.resolve("child");
        SubagentSpawnResult result = center.spawn(new SubagentSpawnRequest(
            "ses_parent",
            "entry_parent",
            "请审查代码",
            childCwd,
            List.of(),
            PermissionMode.DEFAULT_EXECUTE,
            30,
            Optional.empty(),
            Optional.empty()
        ));

        processRunner.complete(new HeadlessSubagentOutput(
            result.childSessionId(),
            SubagentRunStatus.SUCCEEDED,
            "完成摘要",
            Optional.of("entry_final"),
            Optional.empty()
        ));

        assertThat(processRunner.input.cwd()).isEqualTo(childCwd);
        assertThat(processRunner.input.sessionCwd()).isEqualTo(tempDir);
        assertThat(childSessions.request.sessionCwd()).isEqualTo(tempDir);
        assertThat(childSessions.request.cwd()).isEqualTo(childCwd);
        assertThat(sessionFactory.openedCwd).isEqualTo(tempDir);
    }

    private SubagentSpawnRequest request(String parentSessionId, String parentEntryId, String prompt) {
        return new SubagentSpawnRequest(
            parentSessionId,
            parentEntryId,
            prompt,
            tempDir,
            List.of(),
            PermissionMode.DEFAULT_EXECUTE,
            30,
            Optional.empty(),
            Optional.empty()
        );
    }

    private SessionManagerFactoryPort sessionFactory(SessionManagerPort sessionManager) {
        return (cwd, sessionId) -> sessionManager;
    }

    private static final class CapturingSessionFactory implements SessionManagerFactoryPort {
        private final SessionManagerPort sessionManager;
        private Path openedCwd;

        private CapturingSessionFactory(SessionManagerPort sessionManager) {
            this.sessionManager = sessionManager;
        }

        @Override
        public SessionManagerPort open(Path cwd, String sessionId) {
            this.openedCwd = cwd;
            return sessionManager;
        }
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
        private boolean interrupted;
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
                    interrupted = true;
                }
            };
        }

        private void complete(HeadlessSubagentOutput output) {
            completion.complete(output);
        }
    }

    private static final class CapturingParentSession implements SessionManagerPort {
        private final String sessionId;
        private String leafId;
        private final List<SessionEntry> entries = new ArrayList<>();

        private CapturingParentSession(String sessionId, String leafId) {
            this.sessionId = sessionId;
            this.leafId = leafId;
        }

        @Override
        public SessionHandle openOrCreate(String sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SessionHandle append(SessionEntry entry) {
            entries.add(entry);
            leafId = entry.id();
            return new SessionHandle(sessionId, null, leafId, Map.of());
        }

        @Override
        public SessionHandle switchLeaf(String leafId) {
            this.leafId = leafId;
            return new SessionHandle(sessionId, null, leafId, Map.of());
        }

        @Override
        public List<SessionEntry> branch(String leafId) {
            return entries;
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
