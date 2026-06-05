package cn.lypi.agent;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.session.CompactionEntry;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.ModeChangeEntry;
import cn.lypi.contracts.session.ModelChangeEntry;
import cn.lypi.contracts.session.PermissionModeChangeEntry;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.ThinkingChangeEntry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

final class ContextEntryProjector {
    Projection project(List<SessionEntry> leafToRootPath) {
        List<SessionEntry> entries = new ArrayList<>(leafToRootPath);
        Collections.reverse(entries);

        ModelSelection model = new ModelSelection("default", "default", ThinkingLevel.MEDIUM);
        ThinkingLevel thinkingLevel = ThinkingLevel.MEDIUM;
        AgentMode mode = AgentMode.EXECUTE;
        PermissionMode permissionMode = PermissionMode.DEFAULT_EXECUTE;
        List<AgentMessage> messages = new ArrayList<>();
        List<String> branchEntryIds = new ArrayList<>();
        CompactionEntry latestCompaction = null;

        for (SessionEntry entry : entries) {
            branchEntryIds.add(entry.id());
            if (entry instanceof MessageEntry messageEntry) {
                messages.add(messageEntry.message());
            } else if (entry instanceof ModelChangeEntry modelChange) {
                model = modelChange.model();
            } else if (entry instanceof ThinkingChangeEntry thinkingChange) {
                thinkingLevel = thinkingChange.thinkingLevel();
            } else if (entry instanceof ModeChangeEntry modeChange) {
                mode = modeChange.agentMode();
            } else if (entry instanceof PermissionModeChangeEntry permissionChange) {
                permissionMode = permissionChange.permissionMode();
            } else if (entry instanceof CompactionEntry compactionEntry) {
                latestCompaction = compactionEntry;
            }
        }

        List<String> appliedCompactionEntryIds = List.of();
        if (latestCompaction != null) {
            messages = applyCompaction(messages, entries, latestCompaction);
            appliedCompactionEntryIds = List.of(latestCompaction.id());
        }

        return new Projection(
            List.copyOf(messages),
            List.copyOf(branchEntryIds),
            appliedCompactionEntryIds,
            model,
            thinkingLevel,
            mode,
            permissionMode
        );
    }

    private List<AgentMessage> applyCompaction(
        List<AgentMessage> messages,
        List<SessionEntry> entries,
        CompactionEntry compaction
    ) {
        List<AgentMessage> kept = new ArrayList<>();
        boolean keep = false;
        for (SessionEntry entry : entries) {
            if (entry.id().equals(compaction.firstKeptEntryId())) {
                keep = true;
            }
            if (keep && entry instanceof MessageEntry messageEntry) {
                kept.add(messageEntry.message());
            }
        }

        AgentMessage summary = new AgentMessage(
            "summary-" + compaction.id(),
            MessageRole.SYSTEM_LOCAL,
            MessageKind.SUMMARY,
            List.of(new TextContentBlock(compaction.summary())),
            Optional.ofNullable(compaction.timestamp()).orElse(Instant.EPOCH),
            Optional.empty(),
            Optional.empty()
        );

        List<AgentMessage> projected = new ArrayList<>();
        projected.add(summary);
        projected.addAll(kept.isEmpty() ? messages : kept);
        return projected;
    }

    record Projection(
        List<AgentMessage> messages,
        List<String> branchEntryIds,
        List<String> appliedCompactionEntryIds,
        ModelSelection model,
        ThinkingLevel thinkingLevel,
        AgentMode mode,
        PermissionMode permissionMode
    ) {}
}
