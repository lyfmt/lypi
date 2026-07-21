package cn.lypi.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.session.ChildSessionRequest;
import cn.lypi.contracts.session.CustomMessageEntry;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.session.SessionHeader;
import cn.lypi.contracts.session.SessionInfoEntry;
import cn.lypi.contracts.subagent.SubagentToolPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChildSessionServiceTest {
    private static final Instant NOW = Instant.parse("2026-06-09T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void createChildSessionRecordsParentSpawnRelationshipWithoutCopyingParentBranch() throws Exception {
        SessionManager parent = new SessionManagerImpl(tempDir);
        SessionHandle parentHandle = parent.openOrCreate("ses_parent");
        parent.append(new CustomMessageEntry("entry_root", null, "root", NOW));
        parent.append(new CustomMessageEntry("entry_left", "entry_root", "left", NOW));
        parent.append(new CustomMessageEntry("entry_right", "entry_root", "right", NOW));
        int parentLineCount = Files.readAllLines(parentHandle.sessionFile()).size();

        ChildSessionService service = new ChildSessionService(Clock.fixed(NOW, ZoneOffset.UTC));
        SessionHandle child = service.create(new ChildSessionRequest(
            "ses_child",
            "ses_parent",
            "entry_spawn",
            tempDir,
            1,
            Optional.of("reviewer"),
            Optional.of("code-review")
        ));

        assertThat(Files.readAllLines(parentHandle.sessionFile())).hasSize(parentLineCount);
        assertThat(child.sessionId()).isEqualTo("ses_child");
        assertThat(child.byId()).hasSize(1);
        assertThat(child.byId().get(child.leafId()))
            .isInstanceOfSatisfying(SessionInfoEntry.class, entry -> {
                assertThat(entry.parentId()).isNull();
                assertThat(entry.metadata())
                    .containsEntry("parentSessionId", "ses_parent")
                    .containsEntry("parentSpawnEntryId", "entry_spawn")
                    .containsEntry("taskName", "reviewer")
                    .containsEntry("agentName", "reviewer")
                    .containsEntry("agentRole", "code-review");
            });

        String childJsonl = Files.readString(child.sessionFile());
        assertThat(childJsonl)
            .contains("\"parentSessionId\":\"ses_parent\"")
            .contains("\"parentSpawnEntryId\":\"entry_spawn\"")
            .contains("\"depth\":1")
            .contains("\"taskName\":\"reviewer\"")
            .contains("\"agentName\":\"reviewer\"")
            .contains("\"agentRole\":\"code-review\"")
            .doesNotContain("entry_root")
            .doesNotContain("entry_left")
            .doesNotContain("entry_right");
    }

    @Test
    void createChildSessionWritesInitialModelContextIntoHeader() throws Exception {
        SessionManager parent = new SessionManagerImpl(tempDir);
        parent.openOrCreate("ses_parent");
        ChildSessionService service = new ChildSessionService(Clock.fixed(NOW, ZoneOffset.UTC));

        SessionHandle child = service.create(new ChildSessionRequest(
            "ses_child",
            "ses_parent",
            "entry_spawn",
            tempDir,
            tempDir,
            1,
            Optional.of("reviewer"),
            Optional.of("code-review"),
            Optional.of(new ModelSelection("openai", "gpt-5.4", ThinkingLevel.HIGH)),
            Optional.of(ThinkingLevel.HIGH),
            Optional.of(AgentMode.EXECUTE),
            Optional.of(PermissionMode.ASK),
            new SubagentToolPolicy(List.of("bash"), List.of("read", "grep", "glob", "bash"))
        ));

        SessionHeader header = new JsonlSessionStore(tempDir).read(child.sessionId()).header();

        assertThat(header.initialModel()).contains(new ModelSelection("openai", "gpt-5.4", ThinkingLevel.HIGH));
        assertThat(header.initialThinkingLevel()).contains(ThinkingLevel.HIGH);
        assertThat(header.initialAgentMode()).contains(AgentMode.EXECUTE);
        assertThat(header.initialPermissionMode()).contains(PermissionMode.ASK);
    }

    @Test
    void createNestedChildSessionIncrementsParentHeaderDepth() throws Exception {
        SessionManager parent = new SessionManagerImpl(tempDir);
        parent.openOrCreate("ses_parent");

        ChildSessionService service = new ChildSessionService(Clock.fixed(NOW, ZoneOffset.UTC));
        service.create(new ChildSessionRequest(
            "ses_child_1",
            "ses_parent",
            "entry_spawn_1",
            tempDir,
            1,
            Optional.empty(),
            Optional.empty()
        ));
        SessionHandle nested = service.create(new ChildSessionRequest(
            "ses_child_2",
            "ses_child_1",
            "entry_spawn_2",
            tempDir,
            1,
            Optional.empty(),
            Optional.empty()
        ));

        assertThat(Files.readString(nested.sessionFile())).contains("\"depth\":2");
    }

    @Test
    void createChildSessionRejectsMissingRequiredFields() {
        ChildSessionService service = new ChildSessionService(Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.create(new ChildSessionRequest(
            "",
            "ses_parent",
            "entry_spawn",
            tempDir,
            1,
            Optional.empty(),
            Optional.empty()
        )))
            .isInstanceOf(SessionEngineException.class)
            .hasMessageContaining("Child session id is required");

        assertThatThrownBy(() -> service.create(new ChildSessionRequest(
            "ses_child",
            "ses_parent",
            "",
            tempDir,
            1,
            Optional.empty(),
            Optional.empty()
        )))
            .isInstanceOf(SessionEngineException.class)
            .hasMessageContaining("Parent spawn entry id is required");
    }
}
