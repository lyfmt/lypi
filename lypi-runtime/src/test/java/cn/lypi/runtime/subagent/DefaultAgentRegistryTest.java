package cn.lypi.runtime.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.session.CustomMessageEntry;
import cn.lypi.contracts.session.ForkRequest;
import cn.lypi.contracts.session.SessionContext;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.session.SessionView;
import cn.lypi.contracts.subagent.AgentRunStatus;
import cn.lypi.contracts.subagent.MailboxMessage;
import cn.lypi.contracts.subagent.MailboxStatus;
import cn.lypi.contracts.subagent.SubagentRunStatus;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultAgentRegistryTest {
    private static final String PARENT_SESSION_ID = "ses_parent";
    private static final String SPAWN_ENTRY_ID = "entry_spawn_call";

    @TempDir
    Path tempDir;

    @Test
    void projectsCompletedAgentFromChildAndMailboxWithoutLifecycle() {
        ParentSession parent = parentSession();
        DefaultMailboxService mailbox = mailbox();
        mailbox.publish(completion(
            "mail_visible",
            "inspect-session",
            "agent_visible",
            "ses_child_visible",
            SPAWN_ENTRY_ID
        ));
        mailbox.publish(completion(
            "mail_hidden",
            "hidden-task",
            "agent_hidden",
            "ses_child_hidden",
            "entry_other_branch"
        ));
        DefaultAgentRegistry registry = new DefaultAgentRegistry(
            parent,
            mailbox,
            ignored -> List.of(),
            ignored -> List.of(
                child("ses_child_visible", SPAWN_ENTRY_ID, "inspect-session"),
                child("ses_child_hidden", "entry_other_branch", "hidden-task")
            )
        );

        assertThat(registry.list(PARENT_SESSION_ID, Set.of(AgentRunStatus.SUCCEEDED)))
            .singleElement()
            .satisfies(view -> {
                assertThat(view.agentId()).isEqualTo("agent_visible");
                assertThat(view.childSessionId()).isEqualTo("ses_child_visible");
                assertThat(view.parentSpawnEntryId()).isEqualTo(SPAWN_ENTRY_ID);
                assertThat(view.label()).isEqualTo("inspect-session");
                assertThat(view.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
                assertThat(view.summary()).contains("done: inspect-session");
                assertThat(view.finalEntryId()).contains("entry_final");
            });
    }

    @Test
    void projectsOnlyLiveRunsAttachedToCurrentBranch() {
        DefaultAgentRegistry registry = new DefaultAgentRegistry(
            parentSession(),
            mailbox(),
            ignored -> List.of(
                running("agent_visible", "live-task", "ses_child_visible", SPAWN_ENTRY_ID),
                running("agent_hidden", "hidden-task", "ses_child_hidden", "entry_other_branch")
            ),
            ignored -> List.of()
        );

        assertThat(registry.list(PARENT_SESSION_ID, Set.of(AgentRunStatus.RUNNING)))
            .singleElement()
            .satisfies(view -> {
                assertThat(view.agentId()).isEqualTo("agent_visible");
                assertThat(view.label()).isEqualTo("live-task");
                assertThat(view.status()).isEqualTo(AgentRunStatus.RUNNING);
            });
    }

    @Test
    void restoresCompletedAgentFromMailboxWithoutLiveOrChildState() {
        mailbox().publish(completion(
            "mail_persisted",
            "persisted-task",
            "agent_persisted",
            "ses_child_persisted",
            SPAWN_ENTRY_ID
        ));
        DefaultAgentRegistry restarted = new DefaultAgentRegistry(
            parentSession(),
            mailbox(),
            ignored -> List.of(),
            ignored -> List.of()
        );

        assertThat(restarted.list(PARENT_SESSION_ID, Set.of(AgentRunStatus.SUCCEEDED)))
            .singleElement()
            .satisfies(view -> {
                assertThat(view.agentId()).isEqualTo("agent_persisted");
                assertThat(view.childSessionId()).isEqualTo("ses_child_persisted");
                assertThat(view.label()).isEqualTo("persisted-task");
                assertThat(view.summary()).contains("done: persisted-task");
            });
    }

    @Test
    void restoresChildWithoutMailboxOrLiveRunAsUnknown() {
        DefaultAgentRegistry restarted = new DefaultAgentRegistry(
            parentSession(),
            mailbox(),
            ignored -> List.of(),
            ignored -> List.of(child("ses_child_orphaned", SPAWN_ENTRY_ID, "orphaned-task"))
        );

        assertThat(restarted.list(PARENT_SESSION_ID, Set.of(AgentRunStatus.UNKNOWN)))
            .singleElement()
            .satisfies(view -> {
                assertThat(view.childSessionId()).isEqualTo("ses_child_orphaned");
                assertThat(view.parentSpawnEntryId()).isEqualTo(SPAWN_ENTRY_ID);
                assertThat(view.label()).isEqualTo("orphaned-task");
                assertThat(view.status()).isEqualTo(AgentRunStatus.UNKNOWN);
                assertThat(view.summary()).isEmpty();
            });
    }

    private ParentSession parentSession() {
        return new ParentSession(List.of(
            new CustomMessageEntry("entry_root", null, "root", Instant.EPOCH),
            new CustomMessageEntry(SPAWN_ENTRY_ID, "entry_root", "spawn call", Instant.EPOCH)
        ));
    }

    private DefaultMailboxService mailbox() {
        return new DefaultMailboxService(new JsonlMailboxStore(tempDir), Clock.systemUTC());
    }

    private MailboxMessage completion(
        String mailId,
        String taskName,
        String agentId,
        String childSessionId,
        String parentSpawnEntryId
    ) {
        return new MailboxMessage(
            mailId,
            taskName,
            agentId,
            childSessionId,
            "run_" + agentId,
            PARENT_SESSION_ID,
            parentSpawnEntryId,
            SubagentRunStatus.SUCCEEDED,
            "done: " + taskName,
            Optional.of("entry_final"),
            Optional.empty(),
            MailboxStatus.PENDING,
            Instant.EPOCH,
            Instant.EPOCH
        );
    }

    private ChildAgentSnapshot child(String childSessionId, String parentSpawnEntryId, String taskName) {
        return new ChildAgentSnapshot(
            childSessionId,
            PARENT_SESSION_ID,
            parentSpawnEntryId,
            Optional.of(taskName),
            Optional.empty()
        );
    }

    private RunningAgentSnapshot running(
        String agentId,
        String taskName,
        String childSessionId,
        String parentSpawnEntryId
    ) {
        return new RunningAgentSnapshot(
            agentId,
            taskName,
            childSessionId,
            "run_" + agentId,
            PARENT_SESSION_ID,
            parentSpawnEntryId
        );
    }

    private record ParentSession(List<SessionEntry> entries) implements SessionManagerPort {
        @Override public SessionHandle openOrCreate(String sessionId) { return null; }
        @Override public SessionHandle append(SessionEntry entry) { return null; }
        @Override public SessionHandle switchLeaf(String leafId) { return null; }
        @Override public List<SessionEntry> branch(String leafId) { return entries; }
        @Override public SessionView currentView() { return new SessionView(PARENT_SESSION_ID, SPAWN_ENTRY_ID); }
        @Override public SessionView view(String leafId) { return new SessionView(PARENT_SESSION_ID, leafId); }
        @Override public List<AgentMessage> transcript(String leafId) { return List.of(); }
        @Override public SessionContext context(String leafId) {
            return new SessionContext(List.of(), List.of(), List.of(), null, null, null, PermissionMode.ASK);
        }
        @Override public SessionHandle appendMessage(AgentMessage message) { return null; }
        @Override public SessionHandle fork(ForkRequest request) { return null; }
    }
}
