package cn.lypi.session;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.context.ThinkingContentBlock;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.session.ChildSessionRequest;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.ModelChangeEntry;
import cn.lypi.contracts.session.SessionHeader;
import cn.lypi.contracts.tui.SessionResumeInfo;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionResumeQueryTest {
    private static final Instant OLDER = Instant.parse("2026-06-10T00:00:00Z");
    private static final Instant NEWER = Instant.parse("2026-06-10T00:05:00Z");

    @TempDir
    Path tempDir;

    @Test
    void sessionsReturnsPiStyleInfoForCurrentCwdSortedByModifiedDescending() {
        SessionManager older = new SessionManagerImpl(tempDir);
        older.openOrCreate("ses_older");
        older.append(new MessageEntry("entry_older_user", null, message("msg_older_user", MessageRole.USER, "older first", OLDER), OLDER));

        SessionManager newer = new SessionManagerImpl(tempDir);
        newer.openOrCreate("ses_newer");
        newer.append(new MessageEntry("entry_newer_user", null, message("msg_newer_user", MessageRole.USER, "newer first", NEWER), NEWER));
        newer.append(new MessageEntry("entry_newer_assistant", "entry_newer_user", message("msg_newer_assistant", MessageRole.ASSISTANT, "newer reply", NEWER.plusSeconds(1)), NEWER.plusSeconds(1)));

        List<SessionResumeInfo> sessions = new SessionResumeQuery(tempDir).sessions();

        assertThat(sessions).extracting(SessionResumeInfo::sessionId).containsExactly("ses_newer", "ses_older");
        assertThat(sessions.getFirst()).satisfies(session -> {
            assertThat(session.cwd()).isEqualTo(tempDir);
            assertThat(session.leafId()).isEqualTo("entry_newer_assistant");
            assertThat(session.messageCount()).isEqualTo(2);
            assertThat(session.firstMessage()).isEqualTo("newer first");
            assertThat(session.allMessagesText()).contains("newer first", "newer reply");
            assertThat(session.modified()).isEqualTo(NEWER.plusSeconds(1));
            assertThat(session.path().getFileName().toString()).isEqualTo("ses_newer.jsonl");
        });
    }

    @Test
    void sessionsReportLatestConversationLeafWhenLastEntryIsMetadata() {
        SessionManager manager = new SessionManagerImpl(tempDir);
        manager.openOrCreate("ses_main");
        manager.append(new MessageEntry("entry_user", null, message("msg_user", MessageRole.USER, "hello", OLDER), OLDER));
        manager.append(new ModelChangeEntry(
            "entry_model",
            "entry_user",
            new cn.lypi.contracts.model.ModelSelection(
                "openai",
                "gpt-5.4",
                cn.lypi.contracts.model.ThinkingLevel.MEDIUM
            ),
            "test",
            NEWER
        ));

        List<SessionResumeInfo> sessions = new SessionResumeQuery(tempDir).sessions();

        assertThat(sessions.getFirst().leafId()).isEqualTo("entry_user");
    }

    @Test
    void sessionsReportNearestUserLeafWhenLatestEntryIsToolOnlyAssistant() {
        SessionManager manager = new SessionManagerImpl(tempDir);
        manager.openOrCreate("ses_main");
        manager.append(new MessageEntry("entry_user", null, message("msg_user", MessageRole.USER, "hello", OLDER), OLDER));
        manager.append(new MessageEntry(
            "entry_tool_call",
            "entry_user",
            new AgentMessage(
                "msg_tool_call",
                MessageRole.ASSISTANT,
                MessageKind.TOOL_CALL,
                List.of(new ToolCallContentBlock(
                    "toolu_1",
                    "read",
                    "",
                    java.util.Map.of("complete", true, "input", java.util.Map.of("path", "README.md"))
                )),
                NEWER,
                Optional.empty(),
                Optional.of("tool_calls")
            ),
            NEWER
        ));

        List<SessionResumeInfo> sessions = new SessionResumeQuery(tempDir).sessions();

        assertThat(sessions.getFirst().leafId()).isEqualTo("entry_user");
    }

    @Test
    void sessionsReportNearestUserLeafWhenLatestAssistantContainsTextAndToolCall() {
        SessionManager manager = new SessionManagerImpl(tempDir);
        manager.openOrCreate("ses_main");
        manager.append(new MessageEntry("entry_user", null, message("msg_user", MessageRole.USER, "hello", OLDER), OLDER));
        manager.append(new MessageEntry(
            "entry_tool_call",
            "entry_user",
            new AgentMessage(
                "msg_tool_call",
                MessageRole.ASSISTANT,
                MessageKind.TOOL_CALL,
                List.of(
                    new ThinkingContentBlock("thinking"),
                    new TextContentBlock("I will edit it"),
                    new ToolCallContentBlock(
                        "toolu_1",
                        "edit",
                        "",
                        java.util.Map.of("complete", true, "input", java.util.Map.of("path", "main.c"))
                    )
                ),
                NEWER,
                Optional.empty(),
                Optional.of("tool_calls")
            ),
            NEWER
        ));

        List<SessionResumeInfo> sessions = new SessionResumeQuery(tempDir).sessions();

        assertThat(sessions.getFirst().leafId()).isEqualTo("entry_user");
    }

    @Test
    void sessionsIncludeParentSessionPathForThreadedSelector() {
        SessionManager parent = new SessionManagerImpl(tempDir);
        parent.openOrCreate("ses_parent");
        parent.append(new MessageEntry("entry_parent", null, message("msg_parent", MessageRole.USER, "parent", OLDER), OLDER));

        new ChildSessionService(Clock.fixed(NEWER, ZoneOffset.UTC)).create(new ChildSessionRequest(
            "ses_child",
            "ses_parent",
            "entry_parent",
            tempDir,
            1,
            Optional.empty(),
            Optional.empty()
        ));

        List<SessionResumeInfo> sessions = new SessionResumeQuery(tempDir).sessions();

        SessionResumeInfo child = sessions.stream()
            .filter(session -> session.sessionId().equals("ses_child"))
            .findFirst()
            .orElseThrow();
        assertThat(child.parentSessionPath())
            .contains(tempDir.resolve(".ly-pi").resolve("sessions").resolve("ses_parent.jsonl").toAbsolutePath().normalize());
    }

    @Test
    void sessionsSkipUnreadableSessionFiles() throws Exception {
        SessionManager good = new SessionManagerImpl(tempDir);
        good.openOrCreate("ses_good");
        good.append(new MessageEntry("entry_good", null, message("msg_good", MessageRole.USER, "good", NEWER), NEWER));
        Path badFile = tempDir.resolve(".ly-pi").resolve("sessions").resolve("ses_bad.jsonl");
        Files.createDirectories(badFile.getParent());
        Files.writeString(
            badFile,
            """
            {"type":"session","version":1,"id":"ses_bad","cwd":"%s","parentSessionId":null,"parentSpawnEntryId":null,"depth":0,"agentName":null,"agentRole":null,"timestamp":"2026-06-10T00:00:00Z","initialModel":null,"initialThinkingLevel":null,"initialAgentMode":null,"initialPermissionMode":null}
            {"type":"mode_change","id":"bad_mode","parentId":null,"agentMode":"BYPASS","reason":"/mode bypass","timestamp":"2026-06-10T00:00:01Z"}
            """.formatted(tempDir.toString().replace("\\", "\\\\"))
        );

        List<SessionResumeInfo> sessions = new SessionResumeQuery(tempDir).sessions();

        assertThat(sessions).extracting(SessionResumeInfo::sessionId).containsExactly("ses_good");
    }

    @Test
    void sessionsSkipFilesWithUnreadableHeaders() throws Exception {
        SessionManager good = new SessionManagerImpl(tempDir);
        good.openOrCreate("ses_good");
        good.append(new MessageEntry("entry_good", null, message("msg_good", MessageRole.USER, "good", NEWER), NEWER));
        Path badFile = tempDir.resolve(".ly-pi").resolve("sessions").resolve("ses_bad_header.jsonl");
        Files.write(badFile, "{\"type\":\"session\",\"version\":1,\"id\":\"ses_".getBytes(StandardCharsets.UTF_8));
        Files.write(badFile, new byte[] {(byte) 0xC3, (byte) 0x28}, StandardOpenOption.APPEND);
        Files.write(badFile, "\"}\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);

        List<SessionResumeInfo> sessions = new SessionResumeQuery(tempDir).sessions();

        assertThat(sessions).extracting(SessionResumeInfo::sessionId).containsExactly("ses_good");
    }

    @Test
    void sessionsSkipFilesWithUnsafeParentSessionIds() {
        SessionManager good = new SessionManagerImpl(tempDir);
        good.openOrCreate("ses_good");
        good.append(new MessageEntry("entry_good", null, message("msg_good", MessageRole.USER, "good", NEWER), NEWER));
        JsonlSessionStore store = new JsonlSessionStore(tempDir);
        store.create(new SessionHeader("session", 1, "ses_bad_parent", tempDir, Optional.of("bad/parent"), OLDER));
        store.append("ses_bad_parent", new MessageEntry("entry_bad", null, message("msg_bad", MessageRole.USER, "bad", OLDER), OLDER));

        List<SessionResumeInfo> sessions = new SessionResumeQuery(tempDir).sessions();

        assertThat(sessions).extracting(SessionResumeInfo::sessionId).containsExactly("ses_good");
    }

    @Test
    void sessionsSkipFilesWithUnsafeHeaderIds() throws Exception {
        SessionManager good = new SessionManagerImpl(tempDir);
        good.openOrCreate("ses_good");
        good.append(new MessageEntry("entry_good", null, message("msg_good", MessageRole.USER, "good", NEWER), NEWER));
        Path badFile = tempDir.resolve(".ly-pi").resolve("sessions").resolve("ses_bad_id.jsonl");
        Files.writeString(
            badFile,
            """
            {"type":"session","version":1,"id":"bad/id","cwd":"%s","parentSessionId":null,"timestamp":"2026-06-10T00:00:00Z"}
            {"type":"message","id":"entry_bad","parentId":null,"message":{"id":"msg_bad","role":"USER","kind":"TEXT","content":[{"type":"text","text":"bad","metadata":{}}],"timestamp":"2026-06-10T00:00:00Z","usage":null,"stopReason":null},"timestamp":"2026-06-10T00:00:00Z"}
            """.formatted(tempDir.toString().replace("\\", "\\\\"))
        );

        List<SessionResumeInfo> sessions = new SessionResumeQuery(tempDir).sessions();

        assertThat(sessions).extracting(SessionResumeInfo::sessionId).containsExactly("ses_good");
    }

    @Test
    void sessionsSkipFilesWhenHeaderIdDoesNotMatchFileName() {
        SessionManager good = new SessionManagerImpl(tempDir);
        good.openOrCreate("ses_good");
        good.append(new MessageEntry("entry_good", null, message("msg_good", MessageRole.USER, "good", NEWER), NEWER));
        JsonlSessionStore store = new JsonlSessionStore(tempDir);
        store.create(new SessionHeader("session", 1, "ses_header_id", tempDir, Optional.empty(), OLDER));
        Path source = store.sessionFile("ses_header_id");
        Path mismatched = source.resolveSibling("ses_file_id.jsonl");
        try {
            Files.move(source, mismatched);
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }

        List<SessionResumeInfo> sessions = new SessionResumeQuery(tempDir).sessions();

        assertThat(sessions).extracting(SessionResumeInfo::sessionId).containsExactly("ses_good");
    }

    @Test
    void sessionsScanEachFileOnceForResumeInfo() {
        SessionManager manager = new SessionManagerImpl(tempDir);
        manager.openOrCreate("ses_large");
        manager.append(new MessageEntry("entry_user", null, message("msg_user", MessageRole.USER, "first", OLDER), OLDER));
        manager.append(new MessageEntry("entry_assistant", "entry_user", message("msg_assistant", MessageRole.ASSISTANT, "reply", NEWER), NEWER));
        manager.append(new ModelChangeEntry(
            "entry_model",
            "entry_assistant",
            new ModelSelection("openai", "gpt-5.4", ThinkingLevel.MEDIUM),
            "test",
            NEWER.plusSeconds(1)
        ));

        List<SessionResumeInfo> sessions = new SessionResumeQuery(tempDir).sessions();

        assertThat(sessions).singleElement().satisfies(session -> {
            assertThat(session.sessionId()).isEqualTo("ses_large");
            assertThat(session.leafId()).isEqualTo("entry_assistant");
            assertThat(session.messageCount()).isEqualTo(2);
            assertThat(session.firstMessage()).isEqualTo("first");
            assertThat(session.allMessagesText()).contains("first", "reply");
            assertThat(session.modified()).isEqualTo(NEWER.plusSeconds(1));
        });
    }

    @Test
    void sessionsHandleManySessionFilesAndSortByModified() {
        for (int i = 0; i < 50; i++) {
            SessionManager manager = new SessionManagerImpl(tempDir);
            manager.openOrCreate("ses_" + i);
            Instant timestamp = OLDER.plusSeconds(i);
            manager.append(new MessageEntry(
                "entry_" + i,
                null,
                message("msg_" + i, MessageRole.USER, "session " + i, timestamp),
                timestamp
            ));
        }

        List<SessionResumeInfo> sessions = new SessionResumeQuery(tempDir).sessions();

        assertThat(sessions).hasSize(50);
        assertThat(sessions.getFirst().sessionId()).isEqualTo("ses_49");
        assertThat(sessions.getLast().sessionId()).isEqualTo("ses_0");
    }

    @Test
    void resumeScansCollectMetadataInSinglePass() {
        JsonlSessionStore store = new JsonlSessionStore(tempDir);
        store.create(new SessionHeader("session", 1, "ses_scan", tempDir, Optional.empty(), OLDER));
        store.append("ses_scan", new MessageEntry("entry_user", null, message("msg_user", MessageRole.USER, "first", OLDER), OLDER));
        store.append("ses_scan", new MessageEntry("entry_assistant", "entry_user", message("msg_assistant", MessageRole.ASSISTANT, "reply", NEWER), NEWER));
        store.append("ses_scan", new ModelChangeEntry(
            "entry_model",
            "entry_assistant",
            new ModelSelection("openai", "gpt-5.4", ThinkingLevel.MEDIUM),
            "test",
            NEWER.plusSeconds(1)
        ));

        List<SessionResumeScan> scans = store.resumeScans();

        assertThat(scans).singleElement().satisfies(scan -> {
            assertThat(scan.header().id()).isEqualTo("ses_scan");
            assertThat(scan.path()).isEqualTo(store.sessionFile("ses_scan"));
            assertThat(scan.leafId()).isEqualTo("entry_assistant");
            assertThat(scan.messageCount()).isEqualTo(2);
            assertThat(scan.firstMessage()).isEqualTo("first");
            assertThat(scan.allMessagesText()).contains("first", "reply");
            assertThat(scan.modified()).isEqualTo(NEWER.plusSeconds(1));
        });
    }

    @Test
    void sessionsSkipMalformedEntriesBeforeLaterUnreadableBytes() throws Exception {
        SessionManager good = new SessionManagerImpl(tempDir);
        good.openOrCreate("ses_good");
        good.append(new MessageEntry("entry_good", null, message("msg_good", MessageRole.USER, "good", NEWER), NEWER));

        JsonlSessionStore store = new JsonlSessionStore(tempDir);
        store.create(new SessionHeader(
            "session",
            1,
            "ses_bad_entry",
            tempDir,
            Optional.empty(),
            OLDER
        ));
        Path badFile = store.sessionFile("ses_bad_entry");
        Files.write(badFile, "{bad json}\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        Files.write(badFile, new byte[] {(byte) 0xC3, (byte) 0x28}, StandardOpenOption.APPEND);

        List<SessionResumeInfo> sessions = new SessionResumeQuery(tempDir).sessions();

        assertThat(sessions).extracting(SessionResumeInfo::sessionId).containsExactly("ses_good");
    }

    @Test
    void sessionsTreatMissingDisplayTextAsBlank() throws Exception {
        SessionManager good = new SessionManagerImpl(tempDir);
        good.openOrCreate("ses_good");
        good.append(new MessageEntry("entry_good", null, message("msg_good", MessageRole.USER, "good", NEWER), NEWER));
        Path badFile = tempDir.resolve(".ly-pi").resolve("sessions").resolve("ses_bad_text.jsonl");
        Files.writeString(
            badFile,
            """
            {"type":"session","version":1,"id":"ses_bad_text","cwd":"%s","parentSessionId":null,"timestamp":"2026-06-10T00:00:00Z"}
            {"type":"custom_message","id":"entry_bad","parentId":null,"content":null,"timestamp":"2026-06-10T00:00:00Z"}
            {"type":"branch_summary","id":"entry_summary","parentId":"entry_bad","fromId":"entry_bad","summary":null,"timestamp":"2026-06-10T00:00:01Z"}
            """.formatted(tempDir.toString().replace("\\", "\\\\"))
        );

        List<SessionResumeInfo> sessions = new SessionResumeQuery(tempDir).sessions();

        assertThat(sessions).extracting(SessionResumeInfo::sessionId).containsExactly("ses_good", "ses_bad_text");
        assertThat(sessions.get(1)).satisfies(session -> {
            assertThat(session.messageCount()).isZero();
            assertThat(session.firstMessage()).isEqualTo("(no messages)");
            assertThat(session.allMessagesText()).isEmpty();
        });
    }

    @Test
    void sessionsSkipFilesWithMissingHeaderTimestamp() throws Exception {
        SessionManager good = new SessionManagerImpl(tempDir);
        good.openOrCreate("ses_good");
        good.append(new MessageEntry("entry_good", null, message("msg_good", MessageRole.USER, "good", NEWER), NEWER));
        Path badFile = tempDir.resolve(".ly-pi").resolve("sessions").resolve("ses_bad_timestamp.jsonl");
        Files.writeString(
            badFile,
            """
            {"type":"session","version":1,"id":"ses_bad_timestamp","cwd":"%s","parentSessionId":null}
            """.formatted(tempDir.toString().replace("\\", "\\\\"))
        );

        List<SessionResumeInfo> sessions = new SessionResumeQuery(tempDir).sessions();

        assertThat(sessions).extracting(SessionResumeInfo::sessionId).containsExactly("ses_good");
    }

    private AgentMessage message(String id, MessageRole role, String text, Instant timestamp) {
        return new AgentMessage(
            id,
            role,
            MessageKind.TEXT,
            List.of(new TextContentBlock(text)),
            timestamp,
            Optional.empty(),
            Optional.empty()
        );
    }
}
