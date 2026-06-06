package cn.lypi.agent.compact;

import cn.lypi.agent.ContextAssembler;
import cn.lypi.agent.ContextAssembly;
import cn.lypi.agent.ContextBuildRequest;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.event.CompactEndEvent;
import cn.lypi.contracts.event.CompactStartEvent;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.runtime.SessionEnginePort;
import cn.lypi.contracts.session.CompactionEntry;
import cn.lypi.contracts.session.CompactionPlan;
import cn.lypi.contracts.session.SessionEntry;
import java.time.Clock;
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
            sessionEngine.append(compactionEntry);

            ContextBuildRequest rebuildRequest = new ContextBuildRequest(
                request.sessionId(),
                Optional.of(compactionEntry.id()),
                request.cwd(),
                request.contextBuildRequest().includeSystemPrompt()
            );
            ContextAssembly rebuilt = contextAssembler.build(rebuildRequest);
            eventBus.publish(new CompactEndEvent(request.sessionId(), compactionEntry.id(), clock.instant()));
            return new CompactionDecision(rebuilt.snapshot(), plan, true, "compacted");
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
}
