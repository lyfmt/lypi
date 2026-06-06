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
import cn.lypi.contracts.runtime.SessionEnginePort;
import cn.lypi.contracts.session.BranchSummaryEntry;
import cn.lypi.contracts.session.CompactionEntry;
import cn.lypi.contracts.session.CompactionPlan;
import cn.lypi.contracts.session.CustomMessageEntry;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.SessionEntry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class DefaultCompactionCoordinator implements CompactionCoordinator {
    private final SessionEnginePort sessionEngine;
    private final ContextAssembler contextAssembler;
    private final EventBus eventBus;
    private final CompactionPlanner planner;
    private final CompactionSummarizer summarizer;
    private final Clock clock;

    public DefaultCompactionCoordinator(
        SessionEnginePort sessionEngine,
        ContextAssembler contextAssembler,
        EventBus eventBus,
        CompactionPlanner planner,
        CompactionSummarizer summarizer,
        Clock clock
    ) {
        this.sessionEngine = sessionEngine;
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
            String summary = summarizer.summarize(branchEntries, plan.orElseThrow(), assembly.snapshot());
            CompactionEntry compactionEntry = new CompactionEntry(
                "entry-compact-" + UUID.randomUUID(),
                request.leafEntryId().orElse(""),
                summary,
                plan.orElseThrow().firstKeptEntryId(),
                assembly.snapshot().budget().estimatedContextTokens(),
                estimateSummaryTokens(summary),
                plan.orElseThrow().kind(),
                clock.instant()
            );
            ContextSnapshot compactedContext = compactedContext(assembly, branchEntries, compactionEntry);
            sessionEngine.append(compactionEntry);
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
        return sessionEngine.pathToRoot(request.leafEntryId().orElseThrow()).reversed();
    }

    private int estimateSummaryTokens(String summary) {
        return Math.max(1, summary.length() / 4);
    }

    private ContextSnapshot compactedContext(
        ContextAssembly assembly,
        List<SessionEntry> branchEntries,
        CompactionEntry compactionEntry
    ) {
        ContextSnapshot snapshot = assembly.snapshot();
        ContextBudget before = snapshot.budget();
        ContextBudget budget = new ContextBudget(
            Math.min(before.estimatedContextTokens(), compactionEntry.tokensAfter()),
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
            projectedMessages(branchEntries, compactionEntry),
            snapshot.model(),
            snapshot.thinkingLevel(),
            snapshot.mode(),
            snapshot.permissionMode(),
            budget
        );
    }

    private List<AgentMessage> projectedMessages(
        List<SessionEntry> branchEntries,
        CompactionEntry compactionEntry
    ) {
        List<AgentMessage> messages = new ArrayList<>();
        messages.add(systemLocalMessage(
            "summary-" + compactionEntry.id(),
            MessageKind.SUMMARY,
            compactionEntry.summary(),
            compactionEntry.timestamp()
        ));

        boolean keep = false;
        for (SessionEntry entry : branchEntries) {
            if (entry.id().equals(compactionEntry.firstKeptEntryId())) {
                keep = true;
            }
            if (keep) {
                project(entry).ifPresent(messages::add);
            }
        }

        return List.copyOf(messages);
    }

    private void publishCompactEnd(CompactionRequest request, CompactionEntry compactionEntry) {
        try {
            eventBus.publish(new CompactEndEvent(request.sessionId(), compactionEntry.id(), clock.instant()));
        } catch (RuntimeException ignored) {
            // NOTE: Compaction has already been committed; event delivery failure must not undo the decision.
        }
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
}
