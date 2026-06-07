package cn.lypi.agent.compact;

import cn.lypi.agent.ContextAssembler;
import cn.lypi.agent.ContextAssembly;
import cn.lypi.agent.ContextBudgetEstimator;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class DefaultCompactionCoordinator implements CompactionCoordinator {
    private final SessionManagerPort sessionManager;
    private final EventBus eventBus;
    private final CompactionPlanner planner;
    private final CompactionSummarizer summarizer;
    private final ContextBudgetEstimator budgetEstimator;
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
        this.eventBus = eventBus;
        this.planner = planner;
        this.summarizer = summarizer;
        this.budgetEstimator = new ContextBudgetEstimator();
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

        CompactionPlan compactionPlan = plan.orElseThrow();
        try {
            eventBus.publish(new CompactStartEvent(request.sessionId(), compactionPlan.kind().name(), clock.instant()));
        } catch (RuntimeException exception) {
            return new CompactionDecision(assembly.snapshot(), plan, false, "compaction failed: " + exception.getMessage());
        }
        String compactionEntryId = "";
        try {
            CompactSummaryResult result = summarizer.summarize(new CompactSummaryRequest(
                assembly.snapshot(),
                compactionPlan,
                branchEntries,
                request.abortSignal()
            ));
            String summary = summaryText(result);
            int tokensAfter = estimateCompactedTokens(assembly.snapshot(), branchEntries, compactionPlan, summary);
            CompactionEntry compactionEntry = new CompactionEntry(
                "entry-compact-" + UUID.randomUUID(),
                request.leafEntryId().orElse(""),
                summary,
                compactionPlan.firstKeptEntryId(),
                assembly.snapshot().budget().estimatedContextTokens(),
                tokensAfter,
                compactionPlan.kind(),
                clock.instant()
            );
            sessionManager.append(compactionEntry);
            compactionEntryId = compactionEntry.id();
            ContextSnapshot compactedContext = compactedContext(assembly, compactionEntry);
            return new CompactionDecision(compactedContext, plan, true, "compacted");
        } catch (RuntimeException exception) {
            return new CompactionDecision(assembly.snapshot(), plan, false, "compaction failed: " + exception.getMessage());
        } finally {
            publishCompactEnd(request, compactionEntryId);
        }
    }

    private List<SessionEntry> branchEntries(CompactionRequest request) {
        if (request.leafEntryId().isEmpty()) {
            return List.of();
        }
        return sessionManager.branch(request.leafEntryId().orElseThrow());
    }

    private String summaryText(CompactSummaryResult result) {
        if (result == null) {
            throw new IllegalStateException("summary result is null");
        }
        if (result.summary() == null || result.summary().isBlank()) {
            throw new IllegalStateException("summary is empty");
        }
        return result.summary().strip();
    }

    private int estimateCompactedTokens(
        ContextSnapshot snapshot,
        List<SessionEntry> branchEntries,
        CompactionPlan plan,
        String summary
    ) {
        List<AgentMessage> compactedMessages = new ArrayList<>();
        compactedMessages.add(systemLocalMessage(
            "summary-entry-compact-preview",
            MessageKind.SUMMARY,
            summary,
            clock.instant()
        ));

        List<AgentMessage> keptMessages = keptProjectedMessages(branchEntries, plan.firstKeptEntryId());
        compactedMessages.addAll(keptMessages.isEmpty() ? snapshot.messages() : keptMessages);
        ContextSnapshot compactedSnapshot = new ContextSnapshot(
            snapshot.systemPrompt(),
            compactedMessages,
            snapshot.model(),
            snapshot.thinkingLevel(),
            snapshot.mode(),
            snapshot.permissionMode(),
            snapshot.budget()
        );
        return budgetEstimator.estimate(compactedSnapshot).estimatedContextTokens();
    }

    private List<AgentMessage> keptProjectedMessages(List<SessionEntry> branchEntries, String firstKeptEntryId) {
        List<AgentMessage> kept = new ArrayList<>();
        boolean keep = false;
        for (SessionEntry entry : branchEntries) {
            if (entry.id().equals(firstKeptEntryId)) {
                keep = true;
            }
            if (keep) {
                project(entry).ifPresent(kept::add);
            }
        }
        return kept;
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

    private void publishCompactEnd(CompactionRequest request, String compactionEntryId) {
        try {
            eventBus.publish(new CompactEndEvent(request.sessionId(), compactionEntryId, clock.instant()));
        } catch (RuntimeException ignored) {
            // NOTE: Event delivery failure must not alter the already computed compaction decision.
        }
    }

}
