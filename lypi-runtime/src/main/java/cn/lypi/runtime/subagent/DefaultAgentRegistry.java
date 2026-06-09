package cn.lypi.runtime.subagent;

import cn.lypi.contracts.runtime.AgentRegistryPort;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.session.AgentLifecycleEntry;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.subagent.AgentRunStatus;
import cn.lypi.contracts.subagent.AgentView;
import cn.lypi.contracts.subagent.MailboxMessage;
import cn.lypi.contracts.subagent.MailboxStatus;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class DefaultAgentRegistry implements AgentRegistryPort {
    private final SessionManagerPort parentSession;
    private final DefaultMailboxService mailbox;
    private final RunningAgentSnapshotProvider runningAgents;
    private final ChildAgentSnapshotProvider childAgents;

    public DefaultAgentRegistry(
        SessionManagerPort parentSession,
        DefaultMailboxService mailbox,
        RunningAgentSnapshotProvider runningAgents,
        ChildAgentSnapshotProvider childAgents
    ) {
        this.parentSession = Objects.requireNonNull(parentSession, "parentSession must not be null");
        this.mailbox = Objects.requireNonNull(mailbox, "mailbox must not be null");
        this.runningAgents = runningAgents == null ? ignored -> List.of() : runningAgents;
        this.childAgents = childAgents == null ? ignored -> List.of() : childAgents;
    }

    @Override
    public List<AgentView> list(String parentSessionId, Set<AgentRunStatus> statuses) {
        if (parentSessionId == null || parentSessionId.isBlank()) {
            return List.of();
        }
        List<SessionEntry> branch = currentBranch();
        Set<String> branchEntryIds = branchEntryIds(branch);
        Map<String, AgentRecord> records = new LinkedHashMap<>();
        lifecycleRecords(parentSessionId, branch, records);
        childRecords(parentSessionId, branchEntryIds, records);
        runningRecords(parentSessionId, records);
        Map<String, MailboxMessage> mailboxByChildSessionId = mailboxByChildSessionId(parentSessionId);
        return records.values().stream()
            .map(record -> view(record, mailboxByChildSessionId.get(record.childSessionId())))
            .filter(view -> statuses == null || statuses.isEmpty() || statuses.contains(view.status()))
            .toList();
    }

    private List<SessionEntry> currentBranch() {
        String leafId = parentSession.currentView().leafId();
        if (leafId == null || leafId.isBlank()) {
            return List.of();
        }
        return parentSession.branch(leafId);
    }

    private Set<String> branchEntryIds(List<SessionEntry> branch) {
        Set<String> ids = new HashSet<>();
        for (SessionEntry entry : branch) {
            ids.add(entry.id());
        }
        return Set.copyOf(ids);
    }

    private void lifecycleRecords(String parentSessionId, List<SessionEntry> branch, Map<String, AgentRecord> records) {
        for (SessionEntry entry : branch) {
            if (entry instanceof AgentLifecycleEntry lifecycle && parentSessionId.equals(lifecycle.parentSessionId())) {
                AgentRecord record = records.computeIfAbsent(
                    lifecycle.agentId(),
                    ignored -> new AgentRecord(
                        lifecycle.agentId(),
                        lifecycle.childSessionId(),
                        lifecycle.parentSessionId(),
                        lifecycle.id(),
                        Optional.empty(),
                        Optional.empty(),
                        AgentRunStatus.UNKNOWN
                    )
                );
                record.childSessionId = lifecycle.childSessionId();
                record.parentSessionId = lifecycle.parentSessionId();
                record.status = status(lifecycle.lifecycle());
            }
        }
    }

    private void childRecords(String parentSessionId, Set<String> branchEntryIds, Map<String, AgentRecord> records) {
        for (ChildAgentSnapshot child : childAgents.childAgents(parentSessionId)) {
            if (!parentSessionId.equals(child.parentSessionId())) {
                continue;
            }
            if (!branchEntryIds.contains(child.parentSpawnEntryId())) {
                continue;
            }
            Optional<AgentRecord> existing = records.values().stream()
                .filter(record -> child.childSessionId().equals(record.childSessionId))
                .findFirst();
            if (existing.isPresent()) {
                AgentRecord record = existing.get();
                record.agentName = firstPresent(record.agentName, child.agentName());
                record.agentRole = firstPresent(record.agentRole, child.agentRole());
            } else {
                records.put(
                    "child:" + child.childSessionId(),
                    new AgentRecord(
                        "",
                        child.childSessionId(),
                        child.parentSessionId(),
                        child.parentSpawnEntryId(),
                        child.agentName(),
                        child.agentRole(),
                        AgentRunStatus.UNKNOWN
                    )
                );
            }
        }
    }

    private void runningRecords(String parentSessionId, Map<String, AgentRecord> records) {
        for (RunningAgentSnapshot running : runningAgents.runningAgents(parentSessionId)) {
            if (!parentSessionId.equals(running.parentSessionId())) {
                continue;
            }
            AgentRecord record = records.computeIfAbsent(
                running.agentId(),
                ignored -> new AgentRecord(
                    running.agentId(),
                    running.childSessionId(),
                    running.parentSessionId(),
                    running.parentSpawnEntryId(),
                    running.agentName(),
                    running.agentRole(),
                    AgentRunStatus.RUNNING
                )
            );
            record.childSessionId = running.childSessionId();
            record.parentSessionId = running.parentSessionId();
            record.parentSpawnEntryId = running.parentSpawnEntryId();
            record.agentName = firstPresent(running.agentName(), record.agentName);
            record.agentRole = firstPresent(running.agentRole(), record.agentRole);
            record.status = AgentRunStatus.RUNNING;
        }
    }

    private Map<String, MailboxMessage> mailboxByChildSessionId(String parentSessionId) {
        Map<String, MailboxMessage> messages = new LinkedHashMap<>();
        for (MailboxMessage message : mailbox.read(parentSessionId, Set.<MailboxStatus>of())) {
            messages.put(message.childSessionId(), message);
        }
        return messages;
    }

    private AgentView view(AgentRecord record, MailboxMessage mailboxMessage) {
        Optional<MailboxStatus> mailboxStatus = mailboxMessage == null ? Optional.empty() : Optional.of(mailboxMessage.status());
        Optional<String> summary = mailboxMessage == null || blank(mailboxMessage.summary())
            ? Optional.empty()
            : Optional.of(mailboxMessage.summary());
        Optional<String> finalEntryId = mailboxMessage == null || blank(mailboxMessage.contentRef().finalEntryId())
            ? Optional.empty()
            : Optional.of(mailboxMessage.contentRef().finalEntryId());
        return new AgentView(
            record.agentId(),
            label(record),
            record.parentSessionId(),
            record.childSessionId(),
            record.parentSpawnEntryId(),
            record.status(),
            mailboxStatus,
            summary,
            finalEntryId,
            record.agentName(),
            record.agentRole()
        );
    }

    private String label(AgentRecord record) {
        if (record.agentName().isPresent() && record.agentRole().isPresent()) {
            return record.agentName().get() + " [" + record.agentRole().get() + "]";
        }
        if (record.agentName().isPresent()) {
            return record.agentName().get();
        }
        if (record.agentRole().isPresent()) {
            return "[" + record.agentRole().get() + "]";
        }
        return blank(record.agentId()) ? record.childSessionId() : record.agentId();
    }

    private AgentRunStatus status(String lifecycle) {
        if ("finished".equals(lifecycle)) {
            return AgentRunStatus.SUCCEEDED;
        }
        if ("failed".equals(lifecycle)) {
            return AgentRunStatus.FAILED;
        }
        if ("interrupted".equals(lifecycle)) {
            return AgentRunStatus.INTERRUPTED;
        }
        if ("timed_out".equals(lifecycle)) {
            return AgentRunStatus.TIMED_OUT;
        }
        return AgentRunStatus.UNKNOWN;
    }

    private Optional<String> firstPresent(Optional<String> preferred, Optional<String> fallback) {
        return preferred != null && preferred.isPresent() ? preferred : fallback == null ? Optional.empty() : fallback;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static final class AgentRecord {
        private String agentId;
        private String childSessionId;
        private String parentSessionId;
        private String parentSpawnEntryId;
        private Optional<String> agentName;
        private Optional<String> agentRole;
        private AgentRunStatus status;

        private AgentRecord(
            String agentId,
            String childSessionId,
            String parentSessionId,
            String parentSpawnEntryId,
            Optional<String> agentName,
            Optional<String> agentRole,
            AgentRunStatus status
        ) {
            this.agentId = agentId == null ? "" : agentId;
            this.childSessionId = childSessionId == null ? "" : childSessionId;
            this.parentSessionId = parentSessionId == null ? "" : parentSessionId;
            this.parentSpawnEntryId = parentSpawnEntryId == null ? "" : parentSpawnEntryId;
            this.agentName = agentName == null ? Optional.empty() : agentName;
            this.agentRole = agentRole == null ? Optional.empty() : agentRole;
            this.status = status == null ? AgentRunStatus.UNKNOWN : status;
        }

        private String agentId() {
            return agentId;
        }

        private String childSessionId() {
            return childSessionId;
        }

        private String parentSessionId() {
            return parentSessionId;
        }

        private String parentSpawnEntryId() {
            return parentSpawnEntryId;
        }

        private Optional<String> agentName() {
            return agentName;
        }

        private Optional<String> agentRole() {
            return agentRole;
        }

        private AgentRunStatus status() {
            return status;
        }
    }
}
