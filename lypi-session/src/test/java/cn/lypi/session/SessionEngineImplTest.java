package cn.lypi.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.session.CustomMessageEntry;
import cn.lypi.contracts.session.ForkRequest;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.session.SessionHeader;
import cn.lypi.contracts.session.SessionInfoEntry;
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
            .hasMessageContaining("line 2");
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
}
