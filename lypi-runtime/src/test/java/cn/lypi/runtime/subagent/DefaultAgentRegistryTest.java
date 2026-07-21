package cn.lypi.runtime.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.session.AgentLifecycleEntry;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultAgentRegistryTest {
    @TempDir
    Path tempDir;

    @Test
    void projectsTaskNameAndCompletionFromLifecycleAndMailbox() {
        AgentLifecycleEntry spawned = new AgentLifecycleEntry(
            "entry_spawn", null, "agent_1", "ses_child", "ses_parent", "spawned",
            Map.of("taskName", "inspect-session", "runId", "run_1"), Instant.EPOCH
        );
        AgentLifecycleEntry finished = new AgentLifecycleEntry(
            "entry_finish", "entry_spawn", "agent_1", "ses_child", "ses_parent", "finished",
            Map.of("taskName", "inspect-session", "runId", "run_1"), Instant.EPOCH
        );
        ParentSession parent = new ParentSession(List.of(spawned, finished));
        DefaultMailboxService mailbox = new DefaultMailboxService(
            new JsonlMailboxStore(tempDir), Clock.systemUTC()
        );
        mailbox.publish(new MailboxMessage(
            "mail_1", "inspect-session", "agent_1", "ses_child", "run_1", "ses_parent", "entry_spawn",
            SubagentRunStatus.SUCCEEDED, "done", Optional.of("entry_final"), Optional.empty(),
            MailboxStatus.PENDING, Instant.EPOCH, Instant.EPOCH
        ));
        DefaultAgentRegistry registry = new DefaultAgentRegistry(parent, mailbox, ignored -> List.of(), ignored -> List.of());

        assertThat(registry.list("ses_parent", Set.of(AgentRunStatus.SUCCEEDED)))
            .singleElement()
            .satisfies(view -> {
                assertThat(view.label()).isEqualTo("inspect-session");
                assertThat(view.summary()).contains("done");
                assertThat(view.finalEntryId()).contains("entry_final");
            });
    }

    private record ParentSession(List<SessionEntry> entries) implements SessionManagerPort {
        @Override public SessionHandle openOrCreate(String sessionId) { return null; }
        @Override public SessionHandle append(SessionEntry entry) { return null; }
        @Override public SessionHandle switchLeaf(String leafId) { return null; }
        @Override public List<SessionEntry> branch(String leafId) { return entries; }
        @Override public SessionView currentView() { return new SessionView("ses_parent", "entry_finish"); }
        @Override public SessionView view(String leafId) { return currentView(); }
        @Override public List<AgentMessage> transcript(String leafId) { return List.of(); }
        @Override public SessionContext context(String leafId) {
            return new SessionContext(List.of(), List.of(), List.of(), null, null, null, PermissionMode.ASK);
        }
        @Override public SessionHandle appendMessage(AgentMessage message) { return null; }
        @Override public SessionHandle fork(ForkRequest request) { return null; }
    }
}
