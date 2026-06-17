package cn.lypi.session;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.session.BranchSummaryEntry;
import cn.lypi.contracts.session.CompactionEntry;
import cn.lypi.contracts.session.CustomMessageEntry;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.ModeChangeEntry;
import cn.lypi.contracts.session.ModelChangeEntry;
import cn.lypi.contracts.session.PermissionModeChangeEntry;
import cn.lypi.contracts.session.PermissionRuntimeStateChangeEntry;
import cn.lypi.contracts.session.SessionContext;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHeader;
import cn.lypi.contracts.session.ThinkingChangeEntry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Objects;

final class SessionReplayProjector {
    private static final ModelSelection DEFAULT_MODEL = new ModelSelection("default", "default", ThinkingLevel.MEDIUM);

    private final ModelSelection defaultModel;
    private final ThinkingLevel defaultThinkingLevel;
    private final AgentMode defaultMode;
    private final PermissionMode defaultPermissionMode;

    SessionReplayProjector() {
        this(DEFAULT_MODEL, ThinkingLevel.MEDIUM, AgentMode.EXECUTE, PermissionMode.DEFAULT_EXECUTE);
    }

    SessionReplayProjector(
        ModelSelection defaultModel,
        ThinkingLevel defaultThinkingLevel,
        AgentMode defaultMode,
        PermissionMode defaultPermissionMode
    ) {
        this.defaultModel = Objects.requireNonNull(defaultModel, "defaultModel must not be null");
        this.defaultThinkingLevel = Objects.requireNonNull(defaultThinkingLevel, "defaultThinkingLevel must not be null");
        this.defaultMode = Objects.requireNonNull(defaultMode, "defaultMode must not be null");
        this.defaultPermissionMode = Objects.requireNonNull(defaultPermissionMode, "defaultPermissionMode must not be null");
    }

    SessionContext context(List<SessionEntry> branch) {
        return context(null, branch);
    }

    SessionContext context(SessionHeader header, List<SessionEntry> branch) {
        boolean childSession = header != null && header.parentSpawnEntryId().isPresent();
        ModelSelection model = childSession ? header.initialModel().orElse(defaultModel) : defaultModel;
        ThinkingLevel thinkingLevel = childSession ? header.initialThinkingLevel().orElse(defaultThinkingLevel) : defaultThinkingLevel;
        AgentMode mode = childSession ? header.initialAgentMode().orElse(defaultMode) : defaultMode;
        PermissionMode permissionMode = childSession
            ? header.initialPermissionMode().orElse(defaultPermissionMode)
            : defaultPermissionMode;
        PermissionRuntimeState permissionRuntimeState = childSession && header.initialPermissionRuntimeState() != null
            ? header.initialPermissionRuntimeState()
            : PermissionRuntimeState.fromLegacy(permissionMode);
        List<AgentMessage> messages = new ArrayList<>();
        List<String> branchEntryIds = new ArrayList<>();
        CompactionEntry latestCompaction = null;

        for (SessionEntry entry : branch) {
            branchEntryIds.add(entry.id());
            if (entry instanceof MessageEntry messageEntry) {
                messages.add(messageEntry.message());
            } else if (entry instanceof BranchSummaryEntry branchSummary) {
                messages.add(project(branchSummary));
            } else if (entry instanceof CustomMessageEntry customMessage) {
                messages.add(project(customMessage));
            } else if (entry instanceof ModelChangeEntry modelChange) {
                model = modelChange.model();
            } else if (entry instanceof ThinkingChangeEntry thinkingChange) {
                thinkingLevel = thinkingChange.thinkingLevel();
                model = withThinkingLevel(model, thinkingLevel);
            } else if (entry instanceof ModeChangeEntry modeChange) {
                mode = modeChange.agentMode();
            } else if (entry instanceof PermissionModeChangeEntry permissionChange) {
                permissionMode = permissionChange.permissionMode();
                permissionRuntimeState = PermissionRuntimeState.fromLegacy(permissionMode);
            } else if (entry instanceof PermissionRuntimeStateChangeEntry permissionRuntimeChange) {
                permissionRuntimeState = permissionRuntimeChange.permissionRuntimeState();
            } else if (entry instanceof CompactionEntry compactionEntry) {
                latestCompaction = compactionEntry;
            }
        }

        List<String> appliedCompactionEntryIds = List.of();
        if (latestCompaction != null) {
            messages = applyCompaction(messages, branch, latestCompaction);
            appliedCompactionEntryIds = List.of(latestCompaction.id());
        }

        return new SessionContext(
            List.copyOf(messages),
            List.copyOf(branchEntryIds),
            appliedCompactionEntryIds,
            model,
            thinkingLevel,
            mode,
            permissionRuntimeState
        );
    }

    List<AgentMessage> transcript(List<SessionEntry> branch) {
        return context(branch).messages();
    }

    List<AgentMessage> transcript(SessionHeader header, List<SessionEntry> branch) {
        return context(header, branch).messages();
    }

    ModelSelection defaultModel() {
        return defaultModel;
    }

    ThinkingLevel defaultThinkingLevel() {
        return defaultThinkingLevel;
    }

    AgentMode defaultMode() {
        return defaultMode;
    }

    PermissionMode defaultPermissionMode() {
        return defaultPermissionMode;
    }

    private List<AgentMessage> applyCompaction(
        List<AgentMessage> originalMessages,
        List<SessionEntry> branch,
        CompactionEntry compaction
    ) {
        List<AgentMessage> kept = new ArrayList<>();
        boolean keep = false;
        for (SessionEntry entry : branch) {
            if (entry.id().equals(compaction.firstKeptEntryId())) {
                keep = true;
            }
            if (keep) {
                project(entry).ifPresent(kept::add);
            }
        }

        List<AgentMessage> projected = new ArrayList<>();
        projected.add(userSummaryMessage(
            "summary-" + compaction.id(),
            compaction.summary(),
            compaction.timestamp()
        ));
        projected.addAll(kept.isEmpty() ? originalMessages : kept);
        return projected;
    }

    private Optional<AgentMessage> project(SessionEntry entry) {
        if (entry instanceof MessageEntry messageEntry) {
            return Optional.of(messageEntry.message());
        }
        if (entry instanceof BranchSummaryEntry branchSummary) {
            return Optional.of(project(branchSummary));
        }
        if (entry instanceof CustomMessageEntry customMessage) {
            return Optional.of(project(customMessage));
        }
        return Optional.empty();
    }

    private AgentMessage project(BranchSummaryEntry branchSummary) {
        return systemLocalMessage(
            "branch-summary-" + branchSummary.id(),
            MessageKind.SUMMARY,
            branchSummary.summary(),
            branchSummary.timestamp()
        );
    }

    private AgentMessage project(CustomMessageEntry customMessage) {
        return systemLocalMessage(
            "custom-message-" + customMessage.id(),
            MessageKind.TEXT,
            customMessage.content(),
            customMessage.timestamp()
        );
    }

    private AgentMessage systemLocalMessage(String id, MessageKind kind, String text, Instant timestamp) {
        return new AgentMessage(
            id,
            MessageRole.SYSTEM_LOCAL,
            kind,
            List.of(new TextContentBlock(text)),
            Optional.ofNullable(timestamp).orElse(Instant.EPOCH),
            Optional.empty(),
            Optional.empty()
        );
    }

    private AgentMessage userSummaryMessage(String id, String text, Instant timestamp) {
        return new AgentMessage(
            id,
            MessageRole.USER,
            MessageKind.SUMMARY,
            List.of(new TextContentBlock(text)),
            Optional.ofNullable(timestamp).orElse(Instant.EPOCH),
            Optional.empty(),
            Optional.empty()
        );
    }

    private ModelSelection withThinkingLevel(ModelSelection model, ThinkingLevel thinkingLevel) {
        return new ModelSelection(model.provider(), model.modelId(), thinkingLevel);
    }
}
