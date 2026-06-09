package cn.lypi.runtime.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.session.AgentLifecycleEntry;
import cn.lypi.contracts.session.ForkRequest;
import cn.lypi.contracts.session.SessionContext;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.session.SessionView;
import cn.lypi.contracts.subagent.AgentRunStatus;
import cn.lypi.contracts.subagent.AgentView;
import cn.lypi.contracts.subagent.MailboxMessage;
import cn.lypi.contracts.subagent.MailboxStatus;
import cn.lypi.contracts.subagent.SubagentResultRef;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class DefaultAgentRegistryTest {
    private static final Instant NOW = Instant.parse("2026-06-09T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void listsRunningAgentsFromCurrentBranchInSpawnOrder() {
        CapturingParentSession parentSession = new CapturingParentSession("ses_parent", "entry_leaf");
        parentSession.append(new AgentLifecycleEntry(
            "entry_spawn_1",
            "entry_leaf",
            "agent_1",
            "ses_child_1",
            "ses_parent",
            "spawned",
            Map.of(),
            NOW
        ));
        parentSession.append(new AgentLifecycleEntry(
            "entry_spawn_2",
            "entry_spawn_1",
            "agent_2",
            "ses_child_2",
            "ses_parent",
            "spawned",
            Map.of(),
            NOW.plusSeconds(1)
        ));
        DefaultMailboxService mailbox = mailbox(parentSession);
        DefaultAgentRegistry registry = new DefaultAgentRegistry(
            parentSession,
            mailbox,
            parentSessionId -> List.of(
                new RunningAgentSnapshot(
                    "agent_1",
                    "ses_child_1",
                    parentSessionId,
                    "entry_spawn_1",
                    Optional.of("Scout"),
                    Optional.of("explorer")
                )
            ),
            parentSessionId -> List.of()
        );

        List<AgentView> views = registry.list("ses_parent", Set.of());

        assertThat(views)
            .extracting(AgentView::agentId)
            .containsExactly("agent_1", "agent_2");
        assertThat(views.getFirst()).satisfies(view -> {
            assertThat(view.status()).isEqualTo(AgentRunStatus.RUNNING);
            assertThat(view.label()).isEqualTo("Scout [explorer]");
            assertThat(view.childSessionId()).isEqualTo("ses_child_1");
        });
        assertThat(views.get(1)).satisfies(view -> {
            assertThat(view.status()).isEqualTo(AgentRunStatus.UNKNOWN);
            assertThat(view.label()).isEqualTo("agent_2");
        });
    }

    @Test
    void mergesMailboxStatusSummaryAndFinalEntryForCompletedAgent() {
        CapturingParentSession parentSession = new CapturingParentSession("ses_parent", "entry_leaf");
        parentSession.append(new AgentLifecycleEntry(
            "entry_spawn",
            "entry_leaf",
            "agent_1",
            "ses_child",
            "ses_parent",
            "spawned",
            Map.of(),
            NOW
        ));
        parentSession.append(new AgentLifecycleEntry(
            "entry_finished",
            "entry_spawn",
            "agent_1",
            "ses_child",
            "ses_parent",
            "finished",
            Map.of(),
            NOW.plusSeconds(1)
        ));
        DefaultMailboxService mailbox = mailbox(parentSession);
        mailbox.publish(new MailboxMessage(
            "mail_1",
            "agent_1",
            "ses_child",
            "ses_parent",
            "entry_spawn",
            "完成摘要",
            new SubagentResultRef("ses_child", "entry_final", Optional.empty()),
            MailboxStatus.PENDING,
            NOW.plusSeconds(2),
            NOW.plusSeconds(2)
        ));
        DefaultAgentRegistry registry = new DefaultAgentRegistry(
            parentSession,
            mailbox,
            parentSessionId -> List.of(),
            parentSessionId -> List.of()
        );

        List<AgentView> views = registry.list("ses_parent", Set.of(AgentRunStatus.SUCCEEDED));

        assertThat(views).singleElement().satisfies(view -> {
            assertThat(view.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
            assertThat(view.mailboxStatus()).hasValue(MailboxStatus.PENDING);
            assertThat(view.summary()).hasValue("完成摘要");
            assertThat(view.finalEntryId()).hasValue("entry_final");
            assertThat(view.parentSpawnEntryId()).isEqualTo("entry_spawn");
        });
    }

    @Test
    void ignoresChildSnapshotsOutsideCurrentBranch() {
        BranchingParentSession parentSession = new BranchingParentSession("ses_parent", "entry_visible");
        DefaultMailboxService mailbox = mailbox(parentSession);
        DefaultAgentRegistry registry = new DefaultAgentRegistry(
            parentSession,
            mailbox,
            parentSessionId -> List.of(),
            parentSessionId -> List.of(
                new ChildAgentSnapshot(
                    "ses_child_visible",
                    "ses_parent",
                    "entry_visible",
                    Optional.of("Visible"),
                    Optional.empty()
                ),
                new ChildAgentSnapshot(
                    "ses_child_hidden",
                    "ses_parent",
                    "entry_hidden",
                    Optional.of("Hidden"),
                    Optional.empty()
                )
            )
        );

        List<AgentView> views = registry.list("ses_parent", Set.of());

        assertThat(views)
            .extracting(AgentView::childSessionId)
            .containsExactly("ses_child_visible");
    }

    private DefaultMailboxService mailbox(SessionManagerPort parentSession) {
        return new DefaultMailboxService(
            new JsonlMailboxStore(tempDir),
            parentSession,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
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

    private static final class BranchingParentSession implements SessionManagerPort {
        private final String sessionId;
        private final String leafId;

        private BranchingParentSession(String sessionId, String leafId) {
            this.sessionId = sessionId;
            this.leafId = leafId;
        }

        @Override
        public SessionHandle openOrCreate(String sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SessionHandle append(SessionEntry entry) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SessionHandle switchLeaf(String leafId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<SessionEntry> branch(String leafId) {
            return List.of(new AgentLifecycleEntry(
                "entry_visible",
                null,
                "agent_visible",
                "ses_child_visible",
                sessionId,
                "spawned",
                Map.of(),
                NOW
            ));
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
