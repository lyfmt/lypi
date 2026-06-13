package cn.lypi.runtime.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.runtime.ChildSessionPort;
import cn.lypi.contracts.runtime.SessionManagerFactoryPort;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.session.AgentLifecycleEntry;
import cn.lypi.contracts.session.ChildSessionRequest;
import cn.lypi.contracts.session.CustomEntry;
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
import cn.lypi.contracts.subagent.SubagentContinueRequest;
import cn.lypi.contracts.subagent.SubagentContinueResult;
import cn.lypi.contracts.subagent.SubagentSpawnRequest;
import cn.lypi.contracts.subagent.SubagentSpawnResult;
import cn.lypi.contracts.subagent.SubagentToolPolicy;
import cn.lypi.contracts.subagent.SubagentWaitRequest;
import cn.lypi.contracts.subagent.SubagentWaitResult;
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
    void continueRunStartsNewHeadlessContinueRunForExistingChildSession() {
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
        SubagentSpawnResult spawned = center.spawn(request("ses_parent", "entry_parent", "第一轮"));
        processRunner.complete(new HeadlessSubagentOutput(
            spawned.childSessionId(),
            SubagentRunStatus.SUCCEEDED,
            "第一轮完成",
            Optional.of("entry_final_1"),
            Optional.empty()
        ));

        SubagentContinueResult continued = center.continueRun(new SubagentContinueRequest(
            "ses_parent",
            "entry_parent_continue",
            spawned.childSessionId(),
            "第二轮",
            tempDir,
            List.of(),
            30
        ));

        assertThat(continued.status()).isEqualTo(SubagentRunStatus.STARTED);
        assertThat(continued.agentId()).isEqualTo(spawned.agentId());
        assertThat(processRunner.input.childSessionId()).isEqualTo(spawned.childSessionId());
        assertThat(processRunner.input.prompt()).isEqualTo("第二轮");
        assertThat(processRunner.input.runMode()).isEqualTo(cn.lypi.contracts.subagent.HeadlessSubagentRunMode.CONTINUE);
        assertThat(processRunner.input.parentSpawnEntryId()).isEqualTo(continued.parentContinueEntryId());
        assertThat(parentSession.entries)
            .filteredOn(AgentLifecycleEntry.class::isInstance)
            .map(AgentLifecycleEntry.class::cast)
            .extracting(AgentLifecycleEntry::lifecycle)
            .contains("continued");
    }

    @Test
    void spawnInitializesChildContextOnlyFromExplicitRequestFields() {
        CapturingChildSessions childSessions = new CapturingChildSessions();
        CapturingParentSession parentSession = new CapturingParentSession("ses_parent", "entry_parent");
        parentSession.sessionContext = new SessionContext(
            List.of(),
            List.of(),
            List.of(),
            new ModelSelection("parent-provider", "parent-model", ThinkingLevel.MAX),
            ThinkingLevel.MAX,
            AgentMode.PLAN,
            PermissionMode.BYPASS
        );
        CompletingProcessRunner processRunner = new CompletingProcessRunner();
        DefaultAgentCenter center = center(childSessions, parentSession, processRunner);

        center.spawn(new SubagentSpawnRequest(
            "ses_parent",
            "entry_parent",
            "请审查代码",
            tempDir,
            List.of("read", "bash"),
            new SubagentToolPolicy(List.of("read", "bash"), List.of("read", "grep", "glob", "bash")),
            PermissionMode.ACCEPT_EDITS,
            30,
            Optional.empty(),
            Optional.empty(),
            Optional.of(new ModelSelection("openai", "gpt-5.4", ThinkingLevel.HIGH)),
            Optional.of(ThinkingLevel.HIGH),
            Optional.of(AgentMode.EXECUTE)
        ));

        assertThat(childSessions.request.initialModel())
            .contains(new ModelSelection("openai", "gpt-5.4", ThinkingLevel.HIGH));
        assertThat(childSessions.request.initialThinkingLevel()).contains(ThinkingLevel.HIGH);
        assertThat(childSessions.request.initialAgentMode()).contains(AgentMode.EXECUTE);
        assertThat(childSessions.request.initialPermissionMode()).contains(PermissionMode.ACCEPT_EDITS);
        assertThat(processRunner.input.permissionMode()).isEqualTo(PermissionMode.ACCEPT_EDITS);
        assertThat(processRunner.input.toolPolicy().requestedTools()).containsExactly("read", "bash");
        assertThat(processRunner.input.toolPolicy().effectiveTools()).containsExactly("read", "grep", "glob", "bash");
    }

    @Test
    void spawnDoesNotCopyParentContextWhenExplicitFieldsAreMissing() {
        CapturingChildSessions childSessions = new CapturingChildSessions();
        CapturingParentSession parentSession = new CapturingParentSession("ses_parent", "entry_parent");
        parentSession.sessionContext = new SessionContext(
            List.of(),
            List.of(),
            List.of(),
            new ModelSelection("parent-provider", "parent-model", ThinkingLevel.MAX),
            ThinkingLevel.MAX,
            AgentMode.PLAN,
            PermissionMode.BYPASS
        );
        CompletingProcessRunner processRunner = new CompletingProcessRunner();
        DefaultAgentCenter center = center(childSessions, parentSession, processRunner);

        center.spawn(request("ses_parent", "entry_parent", "请审查代码"));

        assertThat(childSessions.request.initialModel()).isEmpty();
        assertThat(childSessions.request.initialThinkingLevel()).isEmpty();
        assertThat(childSessions.request.initialAgentMode()).isEmpty();
        assertThat(childSessions.request.initialPermissionMode()).isEmpty();
        assertThat(processRunner.input.permissionMode()).isEqualTo(PermissionMode.DEFAULT_EXECUTE);
    }

    @Test
    void waitForRunningAgentTimesOutWithoutPublishingMailbox() {
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
        SubagentSpawnResult spawned = center.spawn(request("ses_parent", "entry_parent", "慢任务"));

        SubagentWaitResult result = center.waitFor(new SubagentWaitRequest(
            Optional.of(spawned.agentId()),
            Optional.empty(),
            Optional.empty(),
            1,
            true
        ));

        assertThat(result.status()).isEqualTo(SubagentRunStatus.TIMED_OUT);
        assertThat(mailbox.read("ses_parent", Set.of(MailboxStatus.PENDING))).isEmpty();
    }

    @Test
    void waitForRunningAgentReturnsCompletionAndKeepsSingleMailboxMessage() {
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
        SubagentSpawnResult spawned = center.spawn(request("ses_parent", "entry_parent", "快任务"));
        processRunner.complete(new HeadlessSubagentOutput(
            spawned.childSessionId(),
            SubagentRunStatus.SUCCEEDED,
            "完成摘要",
            Optional.of("entry_final"),
            Optional.empty()
        ));

        SubagentWaitResult result = center.waitFor(new SubagentWaitRequest(
            Optional.of(spawned.agentId()),
            Optional.empty(),
            Optional.empty(),
            1,
            true
        ));

        assertThat(result.agentId()).isEqualTo(spawned.agentId());
        assertThat(result.childSessionId()).isEqualTo(spawned.childSessionId());
        assertThat(result.status()).isEqualTo(SubagentRunStatus.SUCCEEDED);
        assertThat(result.summary()).contains("完成摘要");
        assertThat(result.finalEntryId()).contains("entry_final");
        assertThat(mailbox.read("ses_parent", Set.of(MailboxStatus.PENDING))).hasSize(1);
    }

    @Test
    void waitForRealJsonSubagentProcessPublishesMailboxAndReadableResult() {
        CapturingChildSessions childSessions = new CapturingChildSessions();
        CapturingParentSession parentSession = new CapturingParentSession("ses_parent", "entry_parent");
        DefaultMailboxService mailbox = new DefaultMailboxService(
            new JsonlMailboxStore(tempDir),
            parentSession,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        DefaultAgentCenter center = new DefaultAgentCenter(
            List.of("python3", "-c", """
                import json, sys, time
                data = json.load(sys.stdin)
                time.sleep(0.2)
                print(json.dumps({
                    'childSessionId': data['childSessionId'],
                    'status': 'SUCCEEDED',
                    'summary': 'real wait ok',
                    'finalEntryId': 'entry_real_final'
                }))
                """),
            childSessions,
            parentSession,
            tempDir,
            sessionFactory(parentSession),
            new JsonSubagentProcessRunner(List.of("python3", "-c", """
                import json, sys, time
                data = json.load(sys.stdin)
                time.sleep(0.2)
                print(json.dumps({
                    'childSessionId': data['childSessionId'],
                    'status': 'SUCCEEDED',
                    'summary': 'real wait ok',
                    'finalEntryId': 'entry_real_final'
                }))
                """)),
            mailbox,
            new MailboxDeliveryService(mailbox, ignored -> false),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );

        SubagentSpawnResult spawned = center.spawn(request("ses_parent", "entry_parent", "真实 wait"));
        SubagentWaitResult waited = center.waitFor(new SubagentWaitRequest(
            Optional.of(spawned.agentId()),
            Optional.empty(),
            Optional.empty(),
            5,
            true
        ));

        assertThat(waited.status()).isEqualTo(SubagentRunStatus.SUCCEEDED);
        assertThat(waited.summary()).contains("real wait ok");
        assertThat(waited.finalEntryId()).contains("entry_real_final");
        assertThat(center.readResult(spawned.childSessionId()))
            .hasValueSatisfying(output -> assertThat(output.summary()).isEqualTo("real wait ok"));
        assertThat(mailbox.read("ses_parent", Set.of(MailboxStatus.PENDING)))
            .singleElement()
            .satisfies(message -> {
                assertThat(message.summary()).isEqualTo("real wait ok");
                assertThat(message.contentRef().finalEntryId()).isEqualTo("entry_real_final");
            });
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
    void spawnPassesExplicitModelContextToChildSessionRequest() {
        CapturingChildSessions childSessions = new CapturingChildSessions();
        CapturingParentSession parentSession = new CapturingParentSession("ses_parent", "entry_parent");
        parentSession.sessionContext = new SessionContext(
            List.of(),
            List.of("entry_parent"),
            List.of(),
            new ModelSelection("openai", "gpt-5.4", ThinkingLevel.HIGH),
            ThinkingLevel.HIGH,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE
        );
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

        center.spawn(new SubagentSpawnRequest(
            "ses_parent",
            "entry_parent",
            "请使用指定模型上下文",
            tempDir,
            List.of(),
            new SubagentToolPolicy(List.of(), List.of()),
            PermissionMode.DEFAULT_EXECUTE,
            30,
            Optional.empty(),
            Optional.empty(),
            Optional.of(new ModelSelection("openai", "gpt-5.4", ThinkingLevel.HIGH)),
            Optional.of(ThinkingLevel.HIGH),
            Optional.of(AgentMode.EXECUTE),
            true
        ));

        assertThat(childSessions.request.initialModel())
            .contains(new ModelSelection("openai", "gpt-5.4", ThinkingLevel.HIGH));
        assertThat(childSessions.request.initialThinkingLevel()).contains(ThinkingLevel.HIGH);
        assertThat(childSessions.request.initialAgentMode()).contains(AgentMode.EXECUTE);
        assertThat(childSessions.request.initialPermissionMode()).contains(PermissionMode.DEFAULT_EXECUTE);
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
    void interruptPersistsCommandFactBeforeSubagentCompletes() {
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
        parentSession.switchLeaf("entry_parent");

        center.interrupt(result.agentId());

        assertThat(parentSession.entries)
            .filteredOn(CustomEntry.class::isInstance)
            .map(CustomEntry.class::cast)
            .singleElement()
            .satisfies(entry -> {
                assertThat(entry.parentId()).isEqualTo("entry_parent");
                assertThat(entry.customType()).isEqualTo("agent_command");
                assertThat(entry.data()).containsEntry("action", "interrupt");
                assertThat(entry.data()).containsEntry("agentId", result.agentId());
                assertThat(entry.data()).containsEntry("childSessionId", result.childSessionId());
                assertThat(entry.data()).containsEntry("parentSpawnEntryId", result.parentSpawnEntryId());
            });
    }

    @Test
    void runningAgentsSnapshotTracksStartedAgentsUntilCompletion() {
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
            Optional.of("Scout"),
            Optional.of("explorer")
        ));

        assertThat(center.runningAgents("ses_parent"))
            .singleElement()
            .satisfies(snapshot -> {
                assertThat(snapshot.agentId()).isEqualTo(result.agentId());
                assertThat(snapshot.childSessionId()).isEqualTo(result.childSessionId());
                assertThat(snapshot.parentSpawnEntryId()).isEqualTo(result.parentSpawnEntryId());
                assertThat(snapshot.agentName()).hasValue("Scout");
                assertThat(snapshot.agentRole()).hasValue("explorer");
            });

        processRunner.complete(new HeadlessSubagentOutput(
            result.childSessionId(),
            SubagentRunStatus.SUCCEEDED,
            "完成摘要",
            Optional.of("entry_final"),
            Optional.empty()
        ));

        assertThat(center.runningAgents("ses_parent")).isEmpty();
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

    private DefaultAgentCenter center(
        CapturingChildSessions childSessions,
        CapturingParentSession parentSession,
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
            sessionFactory(parentSession),
            processRunner,
            mailbox,
            new MailboxDeliveryService(mailbox, ignored -> false),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
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
        private SessionContext sessionContext = new SessionContext(
            List.of(),
            List.of(),
            List.of(),
            new ModelSelection("provider", "model", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE
        );

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
            return sessionContext;
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
