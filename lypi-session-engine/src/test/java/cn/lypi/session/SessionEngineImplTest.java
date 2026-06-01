package cn.lypi.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.ContentBlockKind;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.session.CustomMessageEntry;
import cn.lypi.contracts.session.ForkRequest;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHandle;
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
    void appendMessageCreatesMessageEntryThroughAppendSemantics() {
        SessionEngine engine = new SessionEngineImpl(tempDir);
        engine.openOrCreate("ses_main");
        AgentMessage message = new AgentMessage(
            "msg_1",
            MessageRole.USER,
            MessageKind.TEXT,
            List.of(new ContentBlock(ContentBlockKind.TEXT, "hello", Map.of())),
            Instant.parse("2026-06-01T00:00:00Z"),
            Optional.empty(),
            Optional.empty()
        );

        SessionHandle handle = engine.appendMessage(message);

        assertThat(handle.leafId()).isNotBlank();
        assertThat(handle.byId().get(handle.leafId()))
            .isInstanceOfSatisfying(MessageEntry.class, entry -> assertThat(entry.message()).isEqualTo(message));
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
        assertThat(forked.leafId()).isEqualTo("left");
        assertThat(forked.byId()).containsOnlyKeys("root", "left");
        assertThat(Files.readString(forked.sessionFile())).contains("\"parentSessionId\":\"ses_main\"");

        SessionEngine targetEngine = new SessionEngineImpl(targetCwd);
        SessionHandle reopened = targetEngine.openOrCreate(forked.sessionId());
        assertThat(reopened.leafId()).isEqualTo("left");
        assertThat(reopened.byId()).containsOnlyKeys("root", "left");
    }
}
