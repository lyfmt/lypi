package cn.lypi.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.runtime.SessionStorageRootPort;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.session.AgentLifecycleEntry;
import cn.lypi.contracts.session.BranchSummaryEntry;
import cn.lypi.contracts.session.BranchSummaryPlan;
import cn.lypi.contracts.session.CustomMessageEntry;
import cn.lypi.contracts.session.ForkRequest;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.ModeChangeEntry;
import cn.lypi.contracts.session.ModelChangeEntry;
import cn.lypi.contracts.session.PermissionModeChangeEntry;
import cn.lypi.contracts.session.SessionContext;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.session.SessionHeader;
import cn.lypi.contracts.session.SessionInfoEntry;
import cn.lypi.contracts.session.ThinkingChangeEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionManagerImplTest {
    @TempDir
    Path tempDir;

    @Test
    void openOrCreateCreatesHeaderAndRestoresAppendedEntries() throws Exception {
        SessionManager engine = new SessionManagerImpl(tempDir);

        SessionHandle created = engine.openOrCreate("ses_main");
        SessionEntry first = new CustomMessageEntry("entry_1", null, "hello", Instant.parse("2026-06-01T00:00:00Z"));
        SessionEntry second = new CustomMessageEntry("entry_2", "entry_1", "world", Instant.parse("2026-06-01T00:01:00Z"));

        engine.append(first);
        SessionHandle afterSecond = engine.append(second);

        assertThat(afterSecond.leafId()).isEqualTo("entry_2");
        assertThat(Files.readAllLines(created.sessionFile())).hasSize(3);

        SessionManager reopenedEngine = new SessionManagerImpl(tempDir);
        SessionHandle reopened = reopenedEngine.openOrCreate("ses_main");

        assertThat(reopened.sessionId()).isEqualTo("ses_main");
        assertThat(reopened.leafId()).isEqualTo("entry_2");
        assertThat(reopened.byId()).containsKeys("entry_1", "entry_2");
        assertThat(reopenedEngine.branch("entry_2"))
            .extracting(SessionEntry::id)
            .containsExactly("entry_1", "entry_2");
    }

    @Test
    void openTemporaryDoesNotCreateSessionFileUntilUserMessage() {
        SessionManager engine = new SessionManagerImpl(tempDir);

        SessionHandle handle = engine.openTemporary("ses_temp");

        assertThat(handle.leafId()).isNull();
        assertThat(handle.byId()).isEmpty();
        assertThat(handle.sessionFile()).doesNotExist();
    }

    @Test
    void openTemporaryKeepsNonUserEntriesInMemoryWithoutCreatingSessionFile() {
        SessionManager engine = new SessionManagerImpl(tempDir);
        SessionHandle opened = engine.openTemporary("ses_temp");

        SessionHandle afterInfo = engine.append(
            new SessionInfoEntry("entry_info", null, Map.of("source", "slash command"), Instant.parse("2026-06-01T00:00:00Z"))
        );
        SessionHandle afterAssistant = engine.append(
            new MessageEntry(
                "entry_assistant",
                "entry_info",
                assistantMessage("msg_assistant", "thinking"),
                Instant.parse("2026-06-01T00:01:00Z")
            )
        );

        assertThat(opened.sessionFile()).doesNotExist();
        assertThat(afterInfo.sessionFile()).doesNotExist();
        assertThat(afterAssistant.sessionFile()).doesNotExist();
        assertThat(afterAssistant.leafId()).isEqualTo("entry_assistant");
        assertThat(engine.branch("entry_assistant"))
            .extracting(SessionEntry::id)
            .containsExactly("entry_info", "entry_assistant");
    }

    @Test
    void openTemporaryPersistsPendingEntriesWhenFirstUserMessageIsAppended() throws Exception {
        SessionManager engine = new SessionManagerImpl(tempDir);
        SessionHandle opened = engine.openTemporary("ses_temp");
        engine.append(new SessionInfoEntry("entry_info", null, Map.of("source", "slash command"), Instant.parse("2026-06-01T00:00:00Z")));
        engine.append(
            new MessageEntry(
                "entry_assistant",
                "entry_info",
                assistantMessage("msg_assistant", "thinking"),
                Instant.parse("2026-06-01T00:01:00Z")
            )
        );

        SessionHandle persisted = engine.append(
            new MessageEntry(
                "entry_user",
                "entry_assistant",
                textMessage("msg_user", "hello"),
                Instant.parse("2026-06-01T00:02:00Z")
            )
        );

        assertThat(persisted.sessionFile()).exists();
        assertThat(Files.readAllLines(opened.sessionFile())).hasSize(4);
        assertThat(engine.branch("entry_user"))
            .extracting(SessionEntry::id)
            .containsExactly("entry_info", "entry_assistant", "entry_user");

        SessionManager reopenedEngine = new SessionManagerImpl(tempDir);
        SessionHandle reopened = reopenedEngine.openTemporary("ses_temp");

        assertThat(reopened.leafId()).isEqualTo("entry_user");
        assertThat(reopenedEngine.branch("entry_user"))
            .extracting(SessionEntry::id)
            .containsExactly("entry_info", "entry_assistant", "entry_user");
    }

    @Test
    void openTemporaryRestoresExistingSessionInsteadOfUsingPendingMode() {
        SessionManager first = new SessionManagerImpl(tempDir);
        first.openOrCreate("ses_temp");
        first.append(new CustomMessageEntry("entry_1", null, "persisted", Instant.parse("2026-06-01T00:00:00Z")));

        SessionManager reopened = new SessionManagerImpl(tempDir);
        SessionHandle handle = reopened.openTemporary("ses_temp");

        assertThat(handle.sessionFile()).exists();
        assertThat(handle.leafId()).isEqualTo("entry_1");
        assertThat(reopened.branch("entry_1"))
            .extracting(SessionEntry::id)
            .containsExactly("entry_1");
    }

    @Test
    void exposesSessionStorageRootSeparatelyFromExecutionCwd() {
        SessionManager engine = new SessionManagerImpl(tempDir);

        assertThat(engine)
            .isInstanceOfSatisfying(SessionStorageRootPort.class,
                storageRoot -> assertThat(storageRoot.sessionStorageRoot()).isEqualTo(tempDir));
    }

    @Test
    void openOrCreateRejectsSessionIdsThatEscapeSessionDirectory() {
        SessionManager engine = new SessionManagerImpl(tempDir);

        assertThatThrownBy(() -> engine.openOrCreate("../escape"))
            .isInstanceOf(SessionEngineException.class)
            .hasMessageContaining("Invalid session id");

        assertThat(tempDir.resolve(".ly-pi").resolve("escape.jsonl")).doesNotExist();
    }

    @Test
    void openOrCreateRejectsBlankOrUnsafeSessionIds() {
        SessionManager engine = new SessionManagerImpl(tempDir);

        assertThatThrownBy(() -> engine.openOrCreate(""))
            .isInstanceOf(SessionEngineException.class)
            .hasMessageContaining("Invalid session id");
        assertThatThrownBy(() -> engine.openOrCreate("ses:bad"))
            .isInstanceOf(SessionEngineException.class)
            .hasMessageContaining("Invalid session id");
        assertThatThrownBy(() -> engine.openOrCreate("ses/main"))
            .isInstanceOf(SessionEngineException.class)
            .hasMessageContaining("Invalid session id");
    }

    @Test
    void openOrCreateRejectsUnsupportedSessionHeaderVersion() {
        JsonlSessionStore store = new JsonlSessionStore(tempDir);
        store.create(
            new SessionHeader(
                "session",
                99,
                "ses_main",
                tempDir,
                Optional.empty(),
                Instant.parse("2026-06-01T00:00:00Z")
            )
        );
        SessionManager engine = new SessionManagerImpl(tempDir);

        assertThatThrownBy(() -> engine.openOrCreate("ses_main"))
            .isInstanceOf(SessionEngineException.class)
            .hasMessageContaining("Unsupported session version");
    }

    @Test
    void openOrCreateRejectsSessionFileWithDuplicateEntryIds() {
        JsonlSessionStore store = new JsonlSessionStore(tempDir);
        store.create(sessionHeader("ses_main"));
        store.append("ses_main", new CustomMessageEntry("entry_1", null, "first", Instant.parse("2026-06-01T00:01:00Z")));
        store.append("ses_main", new CustomMessageEntry("entry_1", null, "duplicate", Instant.parse("2026-06-01T00:02:00Z")));
        SessionManager engine = new SessionManagerImpl(tempDir);

        assertThatThrownBy(() -> engine.openOrCreate("ses_main"))
            .isInstanceOf(SessionEngineException.class)
            .hasMessageContaining("already exists");
    }

    @Test
    void openOrCreateRejectsSessionFileWithMissingParent() {
        JsonlSessionStore store = new JsonlSessionStore(tempDir);
        store.create(sessionHeader("ses_main"));
        store.append("ses_main", new CustomMessageEntry("entry_1", "missing", "orphan", Instant.parse("2026-06-01T00:01:00Z")));
        SessionManager engine = new SessionManagerImpl(tempDir);

        assertThatThrownBy(() -> engine.openOrCreate("ses_main"))
            .isInstanceOf(SessionEngineException.class)
            .hasMessageContaining("Parent session entry does not exist");
    }

    @Test
    void readRejectsNonSessionHeaderType() throws Exception {
        Path sessionFile = tempDir.resolve(".ly-pi").resolve("sessions").resolve("ses_main.jsonl");
        Files.createDirectories(sessionFile.getParent());
        Files.writeString(sessionFile, "{\"type\":\"message\",\"version\":1,\"id\":\"ses_main\"}\n");
        SessionManager engine = new SessionManagerImpl(tempDir);

        assertThatThrownBy(() -> engine.openOrCreate("ses_main"))
            .isInstanceOf(SessionEngineException.class)
            .hasMessageContaining("First session JSONL line must be a session header");
    }

    @Test
    void openOrCreateReportsLineNumberForMalformedJsonlEntry() throws Exception {
        Path sessionFile = tempDir.resolve(".ly-pi").resolve("sessions").resolve("ses_main.jsonl");
        Files.createDirectories(sessionFile.getParent());
        Files.writeString(
            sessionFile,
            """
            {"type":"session","version":1,"id":"ses_main","cwd":"%s","parentSessionId":null,"timestamp":"2026-06-01T00:00:00Z"}
            not-json
            """.formatted(tempDir.toString())
        );
        SessionManager engine = new SessionManagerImpl(tempDir);

        assertThatThrownBy(() -> engine.openOrCreate("ses_main"))
            .isInstanceOf(SessionEngineException.class)
            .hasMessageContaining("line 2")
            .hasMessageContaining("Unrecognized token");
    }

    @Test
    void appendRejectsDuplicateIdsToPreserveAppendOnlyHistory() {
        SessionManager engine = new SessionManagerImpl(tempDir);
        engine.openOrCreate("ses_main");
        SessionEntry first = new CustomMessageEntry("entry_1", null, "hello", Instant.parse("2026-06-01T00:00:00Z"));
        SessionEntry duplicate = new CustomMessageEntry("entry_1", null, "changed", Instant.parse("2026-06-01T00:01:00Z"));

        engine.append(first);

        assertThatThrownBy(() -> engine.append(duplicate))
            .isInstanceOf(SessionEngineException.class)
            .hasMessageContaining("already exists");
    }

    @Test
    void appendRejectsMissingParent() {
        SessionManager engine = new SessionManagerImpl(tempDir);
        engine.openOrCreate("ses_main");
        SessionEntry orphan = new CustomMessageEntry(
            "entry_1",
            "missing",
            "orphan",
            Instant.parse("2026-06-01T00:00:00Z")
        );

        assertThatThrownBy(() -> engine.append(orphan))
            .isInstanceOf(SessionEngineException.class)
            .hasMessageContaining("Parent session entry does not exist");
    }

    @Test
    void appendRequiresOpenSession() {
        SessionManager engine = new SessionManagerImpl(tempDir);
        SessionEntry first = new CustomMessageEntry("entry_1", null, "hello", Instant.parse("2026-06-01T00:00:00Z"));

        assertThatThrownBy(() -> engine.append(first))
            .isInstanceOf(SessionEngineException.class)
            .hasMessageContaining("Session is not open");
    }

    @Test
    void appendAllowsBranchingFromHistoricalParentAndMovesLeaf() {
        SessionManager engine = new SessionManagerImpl(tempDir);
        engine.openOrCreate("ses_main");
        engine.append(new CustomMessageEntry("root", null, "root", Instant.parse("2026-06-01T00:00:00Z")));
        engine.append(new CustomMessageEntry("left", "root", "left", Instant.parse("2026-06-01T00:01:00Z")));

        SessionHandle branched = engine.append(
            new CustomMessageEntry("right", "root", "right", Instant.parse("2026-06-01T00:02:00Z"))
        );

        assertThat(branched.leafId()).isEqualTo("right");
        assertThat(engine.branch("right")).extracting(SessionEntry::id).containsExactly("root", "right");
        assertThat(engine.branch("left")).extracting(SessionEntry::id).containsExactly("root", "left");
    }

    @Test
    void appendAgentLifecycleEntryMovesInMemoryLeafForCurrentTurnBranching() {
        SessionManager engine = new SessionManagerImpl(tempDir);
        engine.openOrCreate("ses_main");
        engine.append(new CustomMessageEntry("root", null, "root", Instant.parse("2026-06-01T00:00:00Z")));
        engine.append(new CustomMessageEntry("left", "root", "left", Instant.parse("2026-06-01T00:01:00Z")));

        SessionHandle handle = engine.append(new AgentLifecycleEntry(
            "entry_agent",
            "root",
            "agent_1",
            "ses_child",
            "ses_main",
            "finished",
            Map.of(),
            Instant.parse("2026-06-01T00:02:00Z")
        ));

        assertThat(handle.leafId()).isEqualTo("entry_agent");
        assertThat(handle.byId()).containsKey("entry_agent");
        assertThat(engine.branch("entry_agent")).extracting(SessionEntry::id).containsExactly("root", "entry_agent");
    }

    @Test
    void openOrCreateRestoresLatestNonLifecycleLeafWhenLifecycleEntryWasLastJsonlLine() {
        JsonlSessionStore store = new JsonlSessionStore(tempDir);
        store.create(sessionHeader("ses_main"));
        store.append("ses_main", new CustomMessageEntry("root", null, "root", Instant.parse("2026-06-01T00:00:00Z")));
        store.append("ses_main", new CustomMessageEntry("left", "root", "left", Instant.parse("2026-06-01T00:01:00Z")));
        store.append("ses_main", new AgentLifecycleEntry(
            "entry_agent",
            "root",
            "agent_1",
            "ses_child",
            "ses_main",
            "finished",
            Map.of(),
            Instant.parse("2026-06-01T00:02:00Z")
        ));
        SessionManager engine = new SessionManagerImpl(tempDir);

        SessionHandle reopened = engine.openOrCreate("ses_main");

        assertThat(reopened.leafId()).isEqualTo("left");
        assertThat(reopened.byId()).containsKey("entry_agent");
    }

    @Test
    void switchLeafMovesCurrentBranchWithoutAppendingHistory() throws Exception {
        SessionManager engine = new SessionManagerImpl(tempDir);
        SessionHandle opened = engine.openOrCreate("ses_main");
        engine.append(new CustomMessageEntry("root", null, "root", Instant.parse("2026-06-01T00:00:00Z")));
        engine.append(new CustomMessageEntry("left", "root", "left", Instant.parse("2026-06-01T00:01:00Z")));
        int lineCountBeforeSwitch = Files.readAllLines(opened.sessionFile()).size();

        SessionHandle switched = engine.switchLeaf("root");
        SessionHandle branched = engine.append(
            new CustomMessageEntry("right", switched.leafId(), "right", Instant.parse("2026-06-01T00:02:00Z"))
        );

        assertThat(switched.leafId()).isEqualTo("root");
        assertThat(Files.readAllLines(opened.sessionFile())).hasSize(lineCountBeforeSwitch + 1);
        assertThat(branched.leafId()).isEqualTo("right");
        assertThat(engine.branch(branched.leafId())).extracting(SessionEntry::id).containsExactly("root", "right");
    }

    @Test
    void switchLeafDoesNotAppendJsonlLine() throws Exception {
        SessionManager engine = new SessionManagerImpl(tempDir);
        SessionHandle opened = engine.openOrCreate("ses_main");
        engine.append(new CustomMessageEntry("root", null, "root", Instant.parse("2026-06-01T00:00:00Z")));
        int before = Files.readAllLines(opened.sessionFile()).size();

        engine.switchLeaf("root");

        assertThat(Files.readAllLines(opened.sessionFile())).hasSize(before);
    }

    @Test
    void switchLeafRejectsUnknownEntry() {
        SessionManager engine = new SessionManagerImpl(tempDir);
        engine.openOrCreate("ses_main");

        assertThatThrownBy(() -> engine.switchLeaf("missing"))
            .isInstanceOf(SessionEngineException.class)
            .hasMessageContaining("Session entry does not exist");
    }

    @Test
    void collectBranchSummaryPlanReturnsOldPathSuffixToCommonAncestor() {
        SessionManager engine = new SessionManagerImpl(tempDir);
        engine.openOrCreate("ses_main");
        engine.append(new CustomMessageEntry("root", null, "root", Instant.parse("2026-06-01T00:00:00Z")));
        engine.append(new CustomMessageEntry("shared", "root", "shared", Instant.parse("2026-06-01T00:01:00Z")));
        engine.append(new CustomMessageEntry("old-1", "shared", "old 1", Instant.parse("2026-06-01T00:02:00Z")));
        engine.append(new MessageEntry("old-2", "old-1", textMessage("msg-old", "old 2"), Instant.parse("2026-06-01T00:03:00Z")));
        engine.append(new CustomMessageEntry("target", "shared", "target", Instant.parse("2026-06-01T00:04:00Z")));

        BranchSummaryPlan plan = engine.collectBranchSummaryPlan("old-2", "target");

        assertThat(plan.oldLeafId()).isEqualTo("old-2");
        assertThat(plan.targetLeafId()).isEqualTo("target");
        assertThat(plan.commonAncestorId()).contains("shared");
        assertThat(plan.entries()).extracting(SessionEntry::id).containsExactly("old-1", "old-2");
        assertThat(plan.hasSummarizableContent()).isTrue();
    }

    @Test
    void collectBranchSummaryPlanDoesNotOfferForDescendantNavigation() {
        SessionManager engine = new SessionManagerImpl(tempDir);
        engine.openOrCreate("ses_main");
        engine.append(new CustomMessageEntry("root", null, "root", Instant.parse("2026-06-01T00:00:00Z")));
        engine.append(new CustomMessageEntry("child", "root", "child", Instant.parse("2026-06-01T00:01:00Z")));

        BranchSummaryPlan plan = engine.collectBranchSummaryPlan("root", "child");

        assertThat(plan.entries()).isEmpty();
        assertThat(plan.hasSummarizableContent()).isFalse();
    }

    @Test
    void appendBranchSummaryMovesLeafAndProjectsIntoContext() {
        SessionManager engine = new SessionManagerImpl(tempDir);
        engine.openOrCreate("ses_main");
        engine.append(new CustomMessageEntry("root", null, "root", Instant.parse("2026-06-01T00:00:00Z")));
        engine.append(new CustomMessageEntry("target", "root", "target", Instant.parse("2026-06-01T00:01:00Z")));

        SessionHandle handle = engine.appendBranchSummary("target", "old-leaf", "branch summary");

        assertThat(handle.leafId()).isNotBlank();
        assertThat(handle.byId().get(handle.leafId())).isInstanceOf(BranchSummaryEntry.class);
        BranchSummaryEntry summary = (BranchSummaryEntry) handle.byId().get(handle.leafId());
        assertThat(summary.parentId()).isEqualTo("target");
        assertThat(summary.fromId()).isEqualTo("old-leaf");
        assertThat(summary.summary()).isEqualTo("branch summary");
        assertThat(engine.branch(handle.leafId())).extracting(SessionEntry::id).containsExactly("root", "target", handle.leafId());
        assertThat(engine.context(handle.leafId()).messages())
            .extracting(message -> message.content().getFirst().text())
            .contains("branch summary");
    }

    @Test
    void branchReturnsRootToLeafPath() {
        SessionManager manager = new SessionManagerImpl(tempDir);
        manager.openOrCreate("ses_main");
        manager.append(new CustomMessageEntry("root", null, "root", Instant.parse("2026-06-01T00:00:00Z")));
        manager.append(new CustomMessageEntry("left", "root", "left", Instant.parse("2026-06-01T00:01:00Z")));
        manager.append(new CustomMessageEntry("right", "root", "right", Instant.parse("2026-06-01T00:02:00Z")));

        assertThat(manager.branch("left"))
            .extracting(SessionEntry::id)
            .containsExactly("root", "left");
        assertThat(manager.branch("right"))
            .extracting(SessionEntry::id)
            .containsExactly("root", "right");
    }

    @Test
    void branchWithNullLeafReturnsEmptyPath() {
        SessionManager engine = new SessionManagerImpl(tempDir);
        engine.openOrCreate("ses_main");

        assertThat(engine.branch(null)).isEmpty();
    }

    @Test
    void switchLeafAllowsNullToAppendNewRoot() {
        SessionManager engine = new SessionManagerImpl(tempDir);
        engine.openOrCreate("ses_main");
        engine.append(new CustomMessageEntry("root_a", null, "root a", Instant.parse("2026-06-01T00:00:00Z")));

        SessionHandle switched = engine.switchLeaf(null);
        SessionHandle secondRoot = engine.append(
            new CustomMessageEntry("root_b", switched.leafId(), "root b", Instant.parse("2026-06-01T00:01:00Z"))
        );

        assertThat(secondRoot.leafId()).isEqualTo("root_b");
        assertThat(engine.branch("root_b")).extracting(SessionEntry::id).containsExactly("root_b");
    }

    @Test
    void appendMessageCreatesMessageEntryThroughAppendSemantics() {
        SessionManager engine = new SessionManagerImpl(tempDir);
        engine.openOrCreate("ses_main");
        AgentMessage message = textMessage("msg_1", "hello");

        SessionHandle handle = engine.appendMessage(message);

        assertThat(handle.leafId()).isNotBlank();
        assertThat(handle.byId().get(handle.leafId()))
            .isInstanceOfSatisfying(MessageEntry.class, entry -> assertThat(entry.message()).isEqualTo(message));
    }

    @Test
    void appendMessageUsesStableEntryIdPrefix() {
        SessionManager engine = new SessionManagerImpl(tempDir);
        engine.openOrCreate("ses_main");

        SessionHandle handle = engine.appendMessage(textMessage("msg_1", "hello"));

        assertThat(handle.leafId()).startsWith("entry_");
    }

    @Test
    void forkCopiesPathToForkPointIntoTargetCwdSessionStore() throws Exception {
        SessionManager engine = new SessionManagerImpl(tempDir);
        engine.openOrCreate("ses_main");
        engine.append(new CustomMessageEntry("root", null, "root", Instant.parse("2026-06-01T00:00:00Z")));
        engine.append(new CustomMessageEntry("left", "root", "left", Instant.parse("2026-06-01T00:01:00Z")));
        engine.append(new CustomMessageEntry("right", "root", "right", Instant.parse("2026-06-01T00:02:00Z")));
        Path targetCwd = tempDir.resolve("fork-cwd");

        SessionHandle forked = engine.fork(new ForkRequest("ses_main", "left", targetCwd, "explore"));

        assertThat(forked.sessionId()).isNotEqualTo("ses_main");
        assertThat(forked.sessionFile()).startsWith(targetCwd.resolve(".ly-pi").resolve("sessions"));
        assertThat(forked.byId()).containsKeys("root", "left", forked.leafId());
        assertThat(forked.byId().get(forked.leafId()))
            .isInstanceOfSatisfying(SessionInfoEntry.class, entry -> assertThat(entry.parentId()).isEqualTo("left"));
        assertThat(Files.readString(forked.sessionFile())).contains("\"parentSessionId\":\"ses_main\"");

        SessionManager targetEngine = new SessionManagerImpl(targetCwd);
        SessionHandle reopened = targetEngine.openOrCreate(forked.sessionId());
        assertThat(reopened.leafId()).isEqualTo(forked.leafId());
        assertThat(reopened.byId()).containsKeys("root", "left", forked.leafId());
    }

    @Test
    void forkReopenUsesTargetBaselineWhenPathHasNoRuntimeOverrides() {
        ModelSelection sourceModel = new ModelSelection("openai", "gpt-5-mini", ThinkingLevel.HIGH);
        SessionManager sourceEngine = new SessionManagerImpl(
            tempDir,
            sourceModel,
            ThinkingLevel.HIGH,
            AgentMode.PLAN,
            PermissionMode.DEFAULT_EXECUTE
        );
        sourceEngine.openOrCreate("ses_main");
        sourceEngine.append(new CustomMessageEntry("root", null, "root", Instant.parse("2026-06-01T00:00:00Z")));
        Path targetCwd = tempDir.resolve("fork-cwd");

        SessionHandle forked = sourceEngine.fork(new ForkRequest("ses_main", "root", targetCwd, "explore"));

        ModelSelection targetModel = new ModelSelection("anthropic", "claude-sonnet-4", ThinkingLevel.LOW);
        SessionManager targetEngine = new SessionManagerImpl(
            targetCwd,
            targetModel,
            ThinkingLevel.LOW,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE
        );
        targetEngine.openOrCreate(forked.sessionId());
        SessionContext context = targetEngine.context(forked.leafId());
        assertThat(context.model()).isEqualTo(targetModel);
        assertThat(context.thinkingLevel()).isEqualTo(ThinkingLevel.LOW);
        assertThat(context.mode()).isEqualTo(AgentMode.EXECUTE);
        assertThat(context.permissionMode()).isEqualTo(PermissionMode.DEFAULT_EXECUTE);
    }

    @Test
    void forkReopenAppliesCopiedPathRuntimeOverridesOverTargetBaseline() {
        ModelSelection sourceModel = new ModelSelection("openai", "gpt-5-mini", ThinkingLevel.HIGH);
        SessionManager sourceEngine = new SessionManagerImpl(
            tempDir,
            sourceModel,
            ThinkingLevel.HIGH,
            AgentMode.PLAN,
            PermissionMode.DEFAULT_EXECUTE
        );
        sourceEngine.openOrCreate("ses_main");
        sourceEngine.append(new CustomMessageEntry("root", null, "root", Instant.parse("2026-06-01T00:00:00Z")));
        sourceEngine.append(new ModelChangeEntry(
            "model_change",
            "root",
            sourceModel,
            "/model openai/gpt-5-mini",
            Instant.parse("2026-06-01T00:01:00Z")
        ));
        sourceEngine.append(new ThinkingChangeEntry(
            "thinking_change",
            "model_change",
            ThinkingLevel.HIGH,
            "/thinking high",
            Instant.parse("2026-06-01T00:02:00Z")
        ));
        sourceEngine.append(new ModeChangeEntry(
            "mode_change",
            "thinking_change",
            AgentMode.PLAN,
            "/mode plan",
            Instant.parse("2026-06-01T00:03:00Z")
        ));
        sourceEngine.append(new PermissionModeChangeEntry(
            "permission_change",
            "mode_change",
            PermissionMode.ACCEPT_EDITS,
            "/permission-mode accept-edits",
            Instant.parse("2026-06-01T00:04:00Z")
        ));
        Path targetCwd = tempDir.resolve("fork-cwd");

        SessionHandle forked = sourceEngine.fork(new ForkRequest("ses_main", "permission_change", targetCwd, "explore"));

        SessionManager targetEngine = new SessionManagerImpl(
            targetCwd,
            new ModelSelection("anthropic", "claude-sonnet-4", ThinkingLevel.LOW),
            ThinkingLevel.LOW,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE
        );
        targetEngine.openOrCreate(forked.sessionId());
        SessionContext context = targetEngine.context(forked.leafId());
        assertThat(context.model()).isEqualTo(sourceModel);
        assertThat(context.thinkingLevel()).isEqualTo(ThinkingLevel.HIGH);
        assertThat(context.mode()).isEqualTo(AgentMode.PLAN);
        assertThat(context.permissionMode()).isEqualTo(PermissionMode.ACCEPT_EDITS);
    }

    @Test
    void forkPersistsReasonAsSessionInfoWithoutCopyingSiblingBranches() throws Exception {
        SessionManager engine = new SessionManagerImpl(tempDir);
        engine.openOrCreate("ses_main");
        engine.append(new CustomMessageEntry("root", null, "root", Instant.parse("2026-06-01T00:00:00Z")));
        engine.append(new CustomMessageEntry("left", "root", "left", Instant.parse("2026-06-01T00:01:00Z")));
        engine.append(new CustomMessageEntry("right", "root", "right", Instant.parse("2026-06-01T00:02:00Z")));
        Path targetCwd = tempDir.resolve("fork-cwd");

        SessionHandle forked = engine.fork(new ForkRequest("ses_main", "left", targetCwd, "explore branch"));

        assertThat(forked.byId()).doesNotContainKey("right");
        assertThat(Files.readString(forked.sessionFile())).doesNotContain("\"id\":\"right\"");
        assertThat(forked.byId().get(forked.leafId()))
            .isInstanceOfSatisfying(SessionInfoEntry.class, entry -> {
                assertThat(entry.parentId()).isEqualTo("left");
                assertThat(entry.metadata())
                    .containsEntry("forkReason", "explore branch")
                    .containsEntry("sourceSessionId", "ses_main")
                    .containsEntry("forkPointEntryId", "left");
            });
    }

    @Test
    void appendAfterForkUsesForkInfoAsParent() {
        SessionManager engine = new SessionManagerImpl(tempDir);
        engine.openOrCreate("ses_main");
        engine.append(new CustomMessageEntry("root", null, "root", Instant.parse("2026-06-01T00:00:00Z")));
        engine.append(new CustomMessageEntry("left", "root", "left", Instant.parse("2026-06-01T00:01:00Z")));
        Path targetCwd = tempDir.resolve("fork-cwd");

        SessionHandle forked = engine.fork(new ForkRequest("ses_main", "left", targetCwd, "explore branch"));
        SessionManager targetEngine = new SessionManagerImpl(targetCwd);
        targetEngine.openOrCreate(forked.sessionId());

        SessionHandle afterMessage = targetEngine.appendMessage(textMessage("msg_after_fork", "continue"));

        assertThat(targetEngine.branch(afterMessage.leafId()))
            .extracting(SessionEntry::id)
            .containsExactly("root", "left", forked.leafId(), afterMessage.leafId());
    }

    @Test
    void deleteSessionRemovesForkFileWithoutTouchingMainSession() throws Exception {
        SessionManager engine = new SessionManagerImpl(tempDir);
        SessionHandle main = engine.openOrCreate("ses_main");
        engine.append(new CustomMessageEntry("root", null, "root", Instant.parse("2026-06-01T00:00:00Z")));
        Path targetCwd = tempDir.resolve("fork-cwd");
        SessionHandle forked = engine.fork(new ForkRequest("ses_main", "root", targetCwd, "memory_consolidation"));
        SessionManager targetEngine = new SessionManagerImpl(targetCwd);
        targetEngine.openOrCreate(forked.sessionId());
        assertThat(forked.sessionFile()).exists();

        targetEngine.deleteSession(forked.sessionId());

        assertThat(forked.sessionFile()).doesNotExist();
        assertThat(main.sessionFile()).exists();
        SessionHandle afterAppend = engine.appendMessage(textMessage("msg_after_delete", "continue"));
        assertThat(afterAppend.sessionFile()).exists();
    }

    @Test
    void forkRejectsBlankReasonWithSessionEngineException() {
        SessionManager engine = new SessionManagerImpl(tempDir);
        engine.openOrCreate("ses_main");
        engine.append(new CustomMessageEntry("root", null, "root", Instant.parse("2026-06-01T00:00:00Z")));

        assertThatThrownBy(() -> engine.fork(new ForkRequest("ses_main", "root", tempDir.resolve("fork-cwd"), null)))
            .isInstanceOf(SessionEngineException.class)
            .hasMessageContaining("Fork reason is required");
        assertThatThrownBy(() -> engine.fork(new ForkRequest("ses_main", "root", tempDir.resolve("fork-cwd"), " ")))
            .isInstanceOf(SessionEngineException.class)
            .hasMessageContaining("Fork reason is required");
    }

    @Test
    void forkRejectsMissingRequestFieldsWithSessionEngineException() {
        SessionManager engine = new SessionManagerImpl(tempDir);
        engine.openOrCreate("ses_main");
        engine.append(new CustomMessageEntry("root", null, "root", Instant.parse("2026-06-01T00:00:00Z")));

        assertThatThrownBy(() -> engine.fork(null))
            .isInstanceOf(SessionEngineException.class)
            .hasMessageContaining("Fork request is required");
        assertThatThrownBy(() -> engine.fork(new ForkRequest("ses_main", null, tempDir.resolve("fork-cwd"), "explore")))
            .isInstanceOf(SessionEngineException.class)
            .hasMessageContaining("Fork point entry id is required");
        assertThatThrownBy(() -> engine.fork(new ForkRequest("ses_main", "root", null, "explore")))
            .isInstanceOf(SessionEngineException.class)
            .hasMessageContaining("Fork target cwd is required");
        assertThatThrownBy(() -> engine.fork(new ForkRequest(null, "root", tempDir.resolve("fork-cwd"), "explore")))
            .isInstanceOf(SessionEngineException.class)
            .hasMessageContaining("Fork source session id is required");
    }

    @Test
    void createRejectsExistingSessionFileToPreserveAppendOnlyHistory() {
        JsonlSessionStore store = new JsonlSessionStore(tempDir);
        SessionHeader header = new SessionHeader(
            "session",
            1,
            "ses_main",
            tempDir,
            Optional.empty(),
            Instant.parse("2026-06-01T00:00:00Z")
        );
        store.create(header);

        assertThatThrownBy(() -> store.create(header))
            .isInstanceOf(SessionEngineException.class)
            .hasMessageContaining("Session file already exists");
    }

    private static AgentMessage textMessage(String id, String text) {
        return new AgentMessage(
            id,
            MessageRole.USER,
            MessageKind.TEXT,
            List.of(new TextContentBlock(text, Map.of())),
            Instant.parse("2026-06-01T00:00:00Z"),
            Optional.empty(),
            Optional.empty()
        );
    }

    private static AgentMessage assistantMessage(String id, String text) {
        return new AgentMessage(
            id,
            MessageRole.ASSISTANT,
            MessageKind.TEXT,
            List.of(new TextContentBlock(text, Map.of())),
            Instant.parse("2026-06-01T00:00:00Z"),
            Optional.empty(),
            Optional.empty()
        );
    }

    private SessionHeader sessionHeader(String sessionId) {
        return new SessionHeader(
            "session",
            1,
            sessionId,
            tempDir,
            Optional.empty(),
            Instant.parse("2026-06-01T00:00:00Z")
        );
    }
}
