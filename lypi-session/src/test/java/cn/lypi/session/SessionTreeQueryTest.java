package cn.lypi.session;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.session.ChildSessionRequest;
import cn.lypi.contracts.session.CustomMessageEntry;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionTreeQueryTest {
    private static final Instant NOW = Instant.parse("2026-06-09T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void childrenReturnsClosedChildSessionsFromPersistedHeaders() {
        SessionManager parent = new SessionManagerImpl(tempDir);
        parent.openOrCreate("ses_parent");
        parent.append(new CustomMessageEntry("entry_root", null, "root", NOW));

        ChildSessionService service = new ChildSessionService(Clock.fixed(NOW, ZoneOffset.UTC));
        service.create(new ChildSessionRequest(
            "ses_child",
            "ses_parent",
            "entry_spawn",
            tempDir,
            99,
            Optional.of("reviewer"),
            Optional.of("code-review")
        ));

        SessionTreeQuery query = new SessionTreeQuery(tempDir);

        assertThat(query.children("ses_parent"))
            .singleElement()
            .satisfies(child -> {
                assertThat(child.sessionId()).isEqualTo("ses_child");
                assertThat(child.parentSessionId()).contains("ses_parent");
                assertThat(child.parentSpawnEntryId()).contains("entry_spawn");
                assertThat(child.depth()).isEqualTo(1);
                assertThat(child.agentName()).contains("reviewer");
                assertThat(child.agentRole()).contains("code-review");
            });

        assertThat(query.children("missing")).isEmpty();
    }

    @Test
    void childrenReturnsChildStoredInParentSessionCwdWhenExecutionCwdDiffers() {
        SessionManager parent = new SessionManagerImpl(tempDir);
        parent.openOrCreate("ses_parent");
        parent.append(new CustomMessageEntry("entry_root", null, "root", NOW));
        Path executionCwd = tempDir.resolve("child-workdir");

        ChildSessionService service = new ChildSessionService(Clock.fixed(NOW, ZoneOffset.UTC));
        service.create(new ChildSessionRequest(
            "ses_child",
            "ses_parent",
            "entry_spawn",
            tempDir,
            executionCwd,
            1,
            Optional.empty(),
            Optional.empty()
        ));

        assertThat(new SessionTreeQuery(tempDir).children("ses_parent"))
            .singleElement()
            .satisfies(child -> {
                assertThat(child.sessionId()).isEqualTo("ses_child");
                assertThat(child.cwd()).isEqualTo(executionCwd);
            });
    }
}
