package cn.lypi.agent.compact;

import cn.lypi.agent.ContextAssembler;
import cn.lypi.agent.ContextAssembly;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.event.CompactEndEvent;
import cn.lypi.contracts.event.CompactStartEvent;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.session.BranchSummaryEntry;
import cn.lypi.contracts.session.CompactionEntry;
import cn.lypi.contracts.session.CompactionPlan;
import cn.lypi.contracts.session.CustomMessageEntry;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.SessionContext;
import cn.lypi.contracts.session.SessionEntry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class DefaultCompactionCoordinator implements CompactionCoordinator {
    private final SessionManagerPort sessionManager;
    private final ContextAssembler contextAssembler;
    private final EventBus eventBus;
    private final CompactionPlanner planner;
    private final CompactionSummarizer summarizer;
    private final Clock clock;

    public DefaultCompactionCoordinator(
        SessionManagerPort sessionManager,
        ContextAssembler contextAssembler,
        EventBus eventBus,
        CompactionPlanner planner,
        CompactionSummarizer summarizer,
        Clock clock
    ) {
        this.sessionManager = sessionManager;
        this.contextAssembler = contextAssembler;
        this.eventBus = eventBus;
        this.planner = planner;
        this.summarizer = summarizer;
        this.clock = clock;
    }

    @Override
    public CompactionDecision preflight(CompactionRequest request) {
        ContextAssembly assembly = request.assembly();
        List<SessionEntry> branchEntries = branchEntries(request);
        Optional<CompactionPlan> plan = planner.plan(branchEntries, assembly.snapshot());
        if (plan.isEmpty()) {
            return new CompactionDecision(assembly.snapshot(), Optional.empty(), false, "within budget or no safe compaction plan");
        }

        try {
            eventBus.publish(new CompactStartEvent(request.sessionId(), plan.orElseThrow().kind().name(), clock.instant()));
            CompactSummaryResult result = summarizer.summarize(new CompactSummaryRequest(
                assembly.snapshot(),
                plan.orElseThrow(),
                branchEntries,
                request.abortSignal()
            ));
            int tokensAfter = estimateCompactedContextTokens(result.summary(), plan.orElseThrow(), branchEntries);
            CompactionEntry compactionEntry = new CompactionEntry(
                "entry-compact-" + UUID.randomUUID(),
                request.leafEntryId().orElse(""),
                result.summary(),
                plan.orElseThrow().firstKeptEntryId(),
                assembly.snapshot().budget().estimatedContextTokens(),
                tokensAfter,
                plan.orElseThrow().kind(),
                clock.instant()
            );
            sessionManager.append(compactionEntry);
            ContextSnapshot compactedContext = compactedContext(assembly, compactionEntry);
            publishCompactEnd(request, compactionEntry);
            return new CompactionDecision(compactedContext, plan, true, "compacted");
        } catch (RuntimeException exception) {
            return new CompactionDecision(assembly.snapshot(), plan, false, "compaction failed: " + exception.getMessage());
        }
    }

    private List<SessionEntry> branchEntries(CompactionRequest request) {
        if (request.leafEntryId().isEmpty()) {
            return List.of();
        }
        return sessionManager.branch(request.leafEntryId().orElseThrow());
    }

    private int estimateCompactedContextTokens(
        String summary,
        CompactionPlan plan,
        List<SessionEntry> branchEntries
    ) {
        List<AgentMessage> messages = new ArrayList<>();
        messages.add(new AgentMessage(
            "summary-estimate",
            MessageRole.SYSTEM_LOCAL,
            MessageKind.SUMMARY,
            List.of(new TextContentBlock(summary)),
            clock.instant(),
            Optional.empty(),
            Optional.empty()
        ));
        boolean keep = false;
        int keptMessageStart = messages.size();
        for (SessionEntry entry : branchEntries) {
            if (entry.id().equals(plan.firstKeptEntryId())) {
                keep = true;
            }
            if (keep) {
                project(entry).ifPresent(messages::add);
            }
        }
        if (messages.size() == keptMessageStart) {
            messages.addAll(originalMessages(branchEntries));
        }
        return estimateMessages(messages);
    }

    private List<AgentMessage> originalMessages(List<SessionEntry> branchEntries) {
        return branchEntries.stream()
            .map(this::project)
            .flatMap(Optional::stream)
            .toList();
    }

    private Optional<AgentMessage> project(SessionEntry entry) {
        if (entry instanceof MessageEntry messageEntry) {
            return Optional.of(messageEntry.message());
        }
        if (entry instanceof BranchSummaryEntry branchSummary) {
            return Optional.of(systemLocalMessage(
                "branch-summary-" + branchSummary.id(),
                MessageKind.SUMMARY,
                branchSummary.summary(),
                branchSummary.timestamp()
            ));
        }
        if (entry instanceof CustomMessageEntry customMessage) {
            return Optional.of(systemLocalMessage(
                "custom-message-" + customMessage.id(),
                MessageKind.TEXT,
                customMessage.content(),
                customMessage.timestamp()
            ));
        }
        return Optional.empty();
    }

    private AgentMessage systemLocalMessage(
        String id,
        MessageKind kind,
        String text,
        java.time.Instant timestamp
    ) {
        return new AgentMessage(
            id,
            MessageRole.SYSTEM_LOCAL,
            kind,
            List.of(new TextContentBlock(text)),
            timestamp == null ? java.time.Instant.EPOCH : timestamp,
            Optional.empty(),
            Optional.empty()
        );
    }

    private int estimateMessages(List<AgentMessage> messages) {
        int total = 0;
        for (AgentMessage message : messages) {
            total += message.content().stream()
                .map(block -> block.text() == null ? "" : block.text())
                .mapToInt(text -> Math.max(1, text.length() / 4))
                .sum();
        }
        return Math.max(1, total);
    }

    private ContextSnapshot compactedContext(
        ContextAssembly assembly,
        CompactionEntry compactionEntry
    ) {
        ContextSnapshot snapshot = assembly.snapshot();
        SessionContext sessionContext = sessionManager.context(compactionEntry.id());
        ContextBudget before = snapshot.budget();
        ContextBudget budget = new ContextBudget(
            compactionEntry.tokensAfter(),
            before.effectiveContextWindow(),
            before.autoCompactThreshold(),
            before.turnOutputBudget(),
            before.toolResultBudget(),
            before.totalInputTokens(),
            before.totalOutputTokens(),
            before.estimatedCost()
        );
        return new ContextSnapshot(
            snapshot.systemPrompt(),
            sessionContext.messages(),
            sessionContext.model(),
            sessionContext.thinkingLevel(),
            sessionContext.mode(),
            sessionContext.permissionMode(),
            budget
        );
    }

    private void publishCompactEnd(CompactionRequest request, CompactionEntry compactionEntry) {
        try {
            eventBus.publish(new CompactEndEvent(request.sessionId(), compactionEntry.id(), clock.instant()));
        } catch (RuntimeException ignored) {
            // NOTE: Compaction has already been committed; event delivery failure must not undo the decision.
        }
    }

}
