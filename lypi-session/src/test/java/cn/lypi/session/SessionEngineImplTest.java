package cn.lypi.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.audit.AuditKind;
import cn.lypi.contracts.audit.AuditQuery;
import cn.lypi.contracts.audit.AuditRecord;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.session.CommandEntry;
import cn.lypi.contracts.session.CommandKind;
import cn.lypi.contracts.session.CustomMessageEntry;
import cn.lypi.contracts.session.ForkRequest;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.PermissionDecisionEntry;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.session.SessionHeader;
import cn.lypi.contracts.session.SessionInfoEntry;
import cn.lypi.contracts.session.ToolOutputEntry;
import cn.lypi.contracts.session.ToolUseAuditEntry;
import cn.lypi.contracts.tool.ToolExecutionStatus;
import cn.lypi.contracts.tool.ToolOutputRef;
import cn.lypi.contracts.tool.ToolResultSummary;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionEngineImplTest {
    @TempDir
    Path tempDir;

    @Test
    void openOrCreateCreatesHeaderAndRestoresAppendedEntries() throws Exception {
        SessionEngine engine = new SessionEngineImpl(tempDir);

        SessionHandle created = engine.openOrCreate("ses_main");
        SessionEntry first = new CustomMessageEntry("entry_1", null, "hello", Instant.parse("2026-06-01T00:00:00Z"));
        SessionEntry second = new CustomMessageEntry("entry_2", "entry_1", "world", Instant.parse("2026-06-01T00:01:00Z"));

        engine.append(first);
        SessionHandle afterSecond = engine.append(second);

        assertThat(afterSecond.leafId()).isEqualTo("entry_2");
        assertThat(Files.readAllLines(created.sessionFile())).hasSize(3);

        SessionEngine reopenedEngine = new SessionEngineImpl(tempDir);
        SessionHandle reopened = reopenedEngine.openOrCreate("ses_main");

        assertThat(reopened.sessionId()).isEqualTo("ses_main");
        assertThat(reopened.leafId()).isEqualTo("entry_2");
        assertThat(reopened.byId()).containsKeys("entry_1", "entry_2");
        assertThat(reopenedEngine.pathToRoot("entry_2"))
            .extracting(SessionEntry::id)
            .containsExactly("entry_2", "entry_1");
    }

    @Test
    void openOrCreateRestoresReplayableToolAuditEntries() {
        SessionEngine engine = new SessionEngineImpl(tempDir);
        engine.openOrCreate("ses_main");
        Instant startedAt = Instant.parse("2026-06-01T00:00:00Z");
        ToolResultSummary summary = new ToolResultSummary("bash succeeded", "hello", false, 0, false, 42L, Map.of());
        ToolOutputRef ref = new ToolOutputRef(
            "toolout_01",
            "ses_main",
            "toolu_01",
            "text/plain; charset=utf-8",
            "session_blob",
            ".lypi/tool-output/toolout_01.txt",
            "sha256:abc123",
            42L,
            Map.of()
        );

        engine.append(new ToolUseAuditEntry(
            "entry_tool",
            null,
            "toolu_01",
            "msg_parent",
            "turn_01",
            "bash",
            "Bash",
            "echo hello",
            ToolExecutionStatus.SUCCEEDED,
            0,
            summary,
            ref,
            startedAt,
            startedAt.plusSeconds(1),
            1000L,
            Map.of("cwd", "/tmp"),
            startedAt.plusSeconds(1)
        ));

        SessionEngine reopenedEngine = new SessionEngineImpl(tempDir);
        reopenedEngine.openOrCreate("ses_main");

        assertThat(reopenedEngine.pathToRoot("entry_tool"))
            .singleElement()
            .isInstanceOfSatisfying(ToolUseAuditEntry.class, entry -> {
                assertThat(entry.toolUseId()).isEqualTo("toolu_01");
                assertThat(entry.resultRef().contentHash()).isEqualTo("sha256:abc123");
            });
    }

    @Test
    void openOrCreateRestoresAllReplayableEntryTypes() {
        SessionEngine engine = new SessionEngineImpl(tempDir);
        engine.openOrCreate("ses_main");
        Instant timestamp = Instant.parse("2026-06-01T00:00:00Z");
        ToolResultSummary summary = new ToolResultSummary("bash succeeded", "hello", false, 0, false, 42L, Map.of());
        ToolOutputRef ref = toolOutputRef("ses_main", "toolu_01");

        engine.append(new PermissionDecisionEntry(
            "entry_permission",
            null,
            "toolu_01",
            "bash",
            "echo hello",
            new PermissionDecision(
                PermissionBehavior.ALLOW,
                PermissionDecisionReason.TOOL_SPECIFIC,
                "允许",
                Optional.empty(),
                Map.of()
            ),
            timestamp
        ));
        engine.append(new CommandEntry(
            "entry_command",
            "entry_permission",
            CommandKind.STATE_CHANGE,
            "/model gpt-5.4",
            "model",
            Map.of("model", "gpt-5.4"),
            timestamp.plusSeconds(1)
        ));
        engine.append(new ToolOutputEntry(
            "entry_output",
            "entry_command",
            "toolu_01",
            ref,
            summary,
            "sha256:abc123",
            42L,
            Map.of("preview", "hello"),
            timestamp.plusSeconds(2)
        ));

        SessionEngine reopenedEngine = new SessionEngineImpl(tempDir);
        reopenedEngine.openOrCreate("ses_main");

        assertThat(reopenedEngine.pathToRoot("entry_output"))
            .extracting(SessionEntry::id)
            .containsExactly("entry_output", "entry_command", "entry_permission");
    }

    @Test
    void appendWritesAuditRecordsForReplayableFacts() {
        SessionEngine engine = new SessionEngineImpl(tempDir);
        engine.openOrCreate("ses_main");
        Instant timestamp = Instant.parse("2026-06-01T00:00:00Z");
        ToolResultSummary summary = new ToolResultSummary("bash succeeded", "hello", false, 0, false, 42L, Map.of());
        ToolOutputRef ref = toolOutputRef("ses_main", "toolu_01");

        engine.append(new ToolUseAuditEntry(
            "entry_tool",
            null,
            "toolu_01",
            "msg_parent",
            "turn_01",
            "bash",
            "Bash",
            "echo hello",
            ToolExecutionStatus.SUCCEEDED,
            0,
            summary,
            ref,
            timestamp,
            timestamp.plusSeconds(1),
            1000L,
            Map.of("cwd", "/tmp"),
            timestamp.plusSeconds(1)
        ));

        DefaultAuditQueryPort queryPort = new DefaultAuditQueryPort(tempDir);

        assertThat(queryPort.query(new AuditQuery(
            Optional.of("ses_main"),
            Optional.of("entry_tool"),
            Optional.of("toolu_01"),
            Optional.of("msg_parent"),
            Optional.of(AuditKind.TOOL_USE)
        )))
            .extracting(AuditRecord::entryId)
            .containsExactly("entry_tool");
    }

    @Test
    void openOrCreateRejectsSessionIdsThatEscapeSessionDirectory() {
        SessionEngine engine = new SessionEngineImpl(tempDir);

        assertThatThrownBy(() -> engine.openOrCreate("../escape"))
            .isInstanceOf(SessionEngineException.class)
            .hasMessageContaining("Invalid session id");

        assertThat(tempDir.resolve(".lypi").resolve("escape.jsonl")).doesNotExist();
    }

    @Test
    void openOrCreateRejectsBlankOrUnsafeSessionIds() {
        SessionEngine engine = new SessionEngineImpl(tempDir);

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
        SessionEngine engine = new SessionEngineImpl(tempDir);

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
        SessionEngine engine = new SessionEngineImpl(tempDir);

        assertThatThrownBy(() -> engine.openOrCreate("ses_main"))
            .isInstanceOf(SessionEngineException.class)
            .hasMessageContaining("already exists");
    }

    @Test
    void openOrCreateRejectsSessionFileWithMissingParent() {
        JsonlSessionStore store = new JsonlSessionStore(tempDir);
        store.create(sessionHeader("ses_main"));
        store.append("ses_main", new CustomMessageEntry("entry_1", "missing", "orphan", Instant.parse("2026-06-01T00:01:00Z")));
        SessionEngine engine = new SessionEngineImpl(tempDir);

        assertThatThrownBy(() -> engine.openOrCreate("ses_main"))
            .isInstanceOf(SessionEngineException.class)
            .hasMessageContaining("Parent session entry does not exist");
    }

    @Test
    void readRejectsNonSessionHeaderType() throws Exception {
        Path sessionFile = tempDir.resolve(".lypi").resolve("sessions").resolve("ses_main.jsonl");
        Files.createDirectories(sessionFile.getParent());
        Files.writeString(sessionFile, "{\"type\":\"message\",\"version\":1,\"id\":\"ses_main\"}\n");
        SessionEngine engine = new SessionEngineImpl(tempDir);

        assertThatThrownBy(() -> engine.openOrCreate("ses_main"))
            .isInstanceOf(SessionEngineException.class)
            .hasMessageContaining("First session JSONL line must be a session header");
    }

    @Test
    void openOrCreateReportsLineNumberForMalformedJsonlEntry() throws Exception {
        Path sessionFile = tempDir.resolve(".lypi").resolve("sessions").resolve("ses_main.jsonl");
        Files.createDirectories(sessionFile.getParent());
        Files.writeString(
            sessionFile,
            """
            {"type":"session","version":1,"id":"ses_main","cwd":"%s","parentSessionId":null,"timestamp":"2026-06-01T00:00:00Z"}
            not-json
            """.formatted(tempDir.toString())
        );
        SessionEngine engine = new SessionEngineImpl(tempDir);

        assertThatThrownBy(() -> engine.openOrCreate("ses_main"))
            .isInstanceOf(SessionEngineException.class)
            .hasMessageContaining("line 2")
            .hasMessageContaining("Unrecognized token");
    }

    @Test
    void appendRejectsDuplicateIdsToPreserveAppendOnlyHistory() {
        SessionEngine engine = new SessionEngineImpl(tempDir);
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
        SessionEngine engine = new SessionEngineImpl(tempDir);
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
        SessionEngine engine = new SessionEngineImpl(tempDir);
        SessionEntry first = new CustomMessageEntry("entry_1", null, "hello", Instant.parse("2026-06-01T00:00:00Z"));

        assertThatThrownBy(() -> engine.append(first))
            .isInstanceOf(SessionEngineException.class)
            .hasMessageContaining("Session is not open");
    }

    @Test
    void appendAllowsBranchingFromHistoricalParentAndMovesLeaf() {
        SessionEngine engine = new SessionEngineImpl(tempDir);
        engine.openOrCreate("ses_main");
        engine.append(new CustomMessageEntry("root", null, "root", Instant.parse("2026-06-01T00:00:00Z")));
        engine.append(new CustomMessageEntry("left", "root", "left", Instant.parse("2026-06-01T00:01:00Z")));

        SessionHandle branched = engine.append(
            new CustomMessageEntry("right", "root", "right", Instant.parse("2026-06-01T00:02:00Z"))
        );

        assertThat(branched.leafId()).isEqualTo("right");
        assertThat(engine.pathToRoot("right")).extracting(SessionEntry::id).containsExactly("right", "root");
        assertThat(engine.pathToRoot("left")).extracting(SessionEntry::id).containsExactly("left", "root");
    }

    @Test
    void switchLeafMovesCurrentBranchWithoutAppendingHistory() throws Exception {
        SessionEngine engine = new SessionEngineImpl(tempDir);
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
        assertThat(engine.pathToRoot(branched.leafId())).extracting(SessionEntry::id).containsExactly("right", "root");
    }

    @Test
    void switchLeafDoesNotAppendJsonlLine() throws Exception {
        SessionEngine engine = new SessionEngineImpl(tempDir);
        SessionHandle opened = engine.openOrCreate("ses_main");
        engine.append(new CustomMessageEntry("root", null, "root", Instant.parse("2026-06-01T00:00:00Z")));
        int before = Files.readAllLines(opened.sessionFile()).size();

        engine.switchLeaf("root");

        assertThat(Files.readAllLines(opened.sessionFile())).hasSize(before);
    }

    @Test
    void switchLeafRejectsUnknownEntry() {
        SessionEngine engine = new SessionEngineImpl(tempDir);
        engine.openOrCreate("ses_main");

        assertThatThrownBy(() -> engine.switchLeaf("missing"))
            .isInstanceOf(SessionEngineException.class)
            .hasMessageContaining("Session entry does not exist");
    }

    @Test
    void pathToRootWithNullLeafReturnsEmptyPath() {
        SessionEngine engine = new SessionEngineImpl(tempDir);
        engine.openOrCreate("ses_main");

        assertThat(engine.pathToRoot(null)).isEmpty();
    }

    @Test
    void switchLeafAllowsNullToAppendNewRoot() {
        SessionEngine engine = new SessionEngineImpl(tempDir);
        engine.openOrCreate("ses_main");
        engine.append(new CustomMessageEntry("root_a", null, "root a", Instant.parse("2026-06-01T00:00:00Z")));

        SessionHandle switched = engine.switchLeaf(null);
        SessionHandle secondRoot = engine.append(
            new CustomMessageEntry("root_b", switched.leafId(), "root b", Instant.parse("2026-06-01T00:01:00Z"))
        );

        assertThat(secondRoot.leafId()).isEqualTo("root_b");
        assertThat(engine.pathToRoot("root_b")).extracting(SessionEntry::id).containsExactly("root_b");
    }

    @Test
    void appendMessageCreatesMessageEntryThroughAppendSemantics() {
        SessionEngine engine = new SessionEngineImpl(tempDir);
        engine.openOrCreate("ses_main");
        AgentMessage message = textMessage("msg_1", "hello");

        SessionHandle handle = engine.appendMessage(message);

        assertThat(handle.leafId()).isNotBlank();
        assertThat(handle.byId().get(handle.leafId()))
            .isInstanceOfSatisfying(MessageEntry.class, entry -> assertThat(entry.message()).isEqualTo(message));
    }

    @Test
    void appendMessageUsesStableEntryIdPrefix() {
        SessionEngine engine = new SessionEngineImpl(tempDir);
        engine.openOrCreate("ses_main");

        SessionHandle handle = engine.appendMessage(textMessage("msg_1", "hello"));

        assertThat(handle.leafId()).startsWith("entry_");
    }

    @Test
    void forkCopiesPathToForkPointIntoTargetCwdSessionStore() throws Exception {
        SessionEngine engine = new SessionEngineImpl(tempDir);
        engine.openOrCreate("ses_main");
        engine.append(new CustomMessageEntry("root", null, "root", Instant.parse("2026-06-01T00:00:00Z")));
        engine.append(new CustomMessageEntry("left", "root", "left", Instant.parse("2026-06-01T00:01:00Z")));
        engine.append(new CustomMessageEntry("right", "root", "right", Instant.parse("2026-06-01T00:02:00Z")));
        Path targetCwd = tempDir.resolve("fork-cwd");

        SessionHandle forked = engine.fork(new ForkRequest("ses_main", "left", targetCwd, "explore"));

        assertThat(forked.sessionId()).isNotEqualTo("ses_main");
        assertThat(forked.sessionFile()).startsWith(targetCwd.resolve(".lypi").resolve("sessions"));
        assertThat(forked.byId()).containsKeys("root", "left", forked.leafId());
        assertThat(forked.byId().get(forked.leafId()))
            .isInstanceOfSatisfying(SessionInfoEntry.class, entry -> assertThat(entry.parentId()).isEqualTo("left"));
        assertThat(Files.readString(forked.sessionFile())).contains("\"parentSessionId\":\"ses_main\"");

        SessionEngine targetEngine = new SessionEngineImpl(targetCwd);
        SessionHandle reopened = targetEngine.openOrCreate(forked.sessionId());
        assertThat(reopened.leafId()).isEqualTo(forked.leafId());
        assertThat(reopened.byId()).containsKeys("root", "left", forked.leafId());
    }

    @Test
    void forkPersistsReasonAsSessionInfoWithoutCopyingSiblingBranches() throws Exception {
        SessionEngine engine = new SessionEngineImpl(tempDir);
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
        SessionEngine engine = new SessionEngineImpl(tempDir);
        engine.openOrCreate("ses_main");
        engine.append(new CustomMessageEntry("root", null, "root", Instant.parse("2026-06-01T00:00:00Z")));
        engine.append(new CustomMessageEntry("left", "root", "left", Instant.parse("2026-06-01T00:01:00Z")));
        Path targetCwd = tempDir.resolve("fork-cwd");

        SessionHandle forked = engine.fork(new ForkRequest("ses_main", "left", targetCwd, "explore branch"));
        SessionEngine targetEngine = new SessionEngineImpl(targetCwd);
        targetEngine.openOrCreate(forked.sessionId());

        SessionHandle afterMessage = targetEngine.appendMessage(textMessage("msg_after_fork", "continue"));

        assertThat(targetEngine.pathToRoot(afterMessage.leafId()))
            .extracting(SessionEntry::id)
            .containsExactly(afterMessage.leafId(), forked.leafId(), "left", "root");
    }

    @Test
    void forkRejectsBlankReasonWithSessionEngineException() {
        SessionEngine engine = new SessionEngineImpl(tempDir);
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
        SessionEngine engine = new SessionEngineImpl(tempDir);
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

    private ToolOutputRef toolOutputRef(String sessionId, String toolUseId) {
        return new ToolOutputRef(
            "toolout_01",
            sessionId,
            toolUseId,
            "text/plain; charset=utf-8",
            "session_blob",
            ".lypi/tool-output/toolout_01.txt",
            "sha256:abc123",
            42L,
            Map.of()
        );
    }
}
