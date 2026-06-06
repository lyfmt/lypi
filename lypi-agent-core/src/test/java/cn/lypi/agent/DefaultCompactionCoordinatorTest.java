package cn.lypi.agent;

import cn.lypi.agent.compact.CompactionDecision;
import cn.lypi.agent.compact.CompactionRequest;
import cn.lypi.agent.compact.CompactionSummarizer;
import cn.lypi.agent.compact.CompactSummaryResult;
import cn.lypi.agent.compact.DefaultCompactionCoordinator;
import cn.lypi.agent.compact.DefaultCompactionPlanner;
import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.event.CompactEndEvent;
import cn.lypi.contracts.event.CompactStartEvent;
import cn.lypi.contracts.model.TokenUsage;
import cn.lypi.contracts.session.BranchSummaryEntry;
import cn.lypi.contracts.session.CompactionEntry;
import cn.lypi.contracts.session.CustomMessageEntry;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.ModelChangeEntry;
import cn.lypi.contracts.session.SessionEntry;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static cn.lypi.agent.AgentCoreTestFixtures.NOW;
import static cn.lypi.agent.AgentCoreTestFixtures.assistantMessage;
import static cn.lypi.agent.AgentCoreTestFixtures.fixedResourceRuntime;
import static cn.lypi.agent.AgentCoreTestFixtures.userMessage;
import static org.assertj.core.api.Assertions.assertThat;

class DefaultCompactionCoordinatorTest {
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void appendsCompactionEntryAndReturnsRebuiltContext() {
        AgentCoreTestFixtures.InMemorySessionManager session = sessionWithLongBranch();
        DefaultContextAssembler assembler = lowBudgetAssembler(session);
        ContextBuildRequest buildRequest = buildRequest(session);
        ContextAssembly assembly = assembler.build(buildRequest);
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        DefaultCompactionCoordinator coordinator = new DefaultCompactionCoordinator(
            session,
            assembler,
            eventBus,
            new DefaultCompactionPlanner(4),
            compactRequest -> new CompactSummaryResult("summary text", new TokenUsage(0, 0, 0, 0)),
            CLOCK
        );

        CompactionDecision decision = coordinator.preflight(request(session, buildRequest, assembly));

        assertThat(decision.compacted()).isTrue();
        assertThat(session.handle().byId().values())
            .filteredOn(CompactionEntry.class::isInstance)
            .hasSize(1);
        assertThat(decision.context().messages())
            .extracting(AgentMessage::kind)
            .startsWith(MessageKind.SUMMARY);
        assertThat(decision.context().messages().getFirst().content().getFirst().text()).isEqualTo("summary text");
        assertThat(eventBus.events)
            .extracting(Object::getClass)
            .containsSubsequence(CompactStartEvent.class, CompactEndEvent.class);
    }

    @Test
    void fallsBackToOriginalContextWhenSummarizerFails() {
        AgentCoreTestFixtures.InMemorySessionManager session = sessionWithLongBranch();
        DefaultContextAssembler assembler = lowBudgetAssembler(session);
        ContextBuildRequest buildRequest = buildRequest(session);
        ContextAssembly assembly = assembler.build(buildRequest);
        CompactionSummarizer failingSummarizer = compactRequest -> {
            throw new IllegalStateException("summary unavailable");
        };
        DefaultCompactionCoordinator coordinator = new DefaultCompactionCoordinator(
            session,
            assembler,
            new AgentCoreTestFixtures.RecordingEventBus(),
            new DefaultCompactionPlanner(4),
            failingSummarizer,
            CLOCK
        );

        CompactionDecision decision = coordinator.preflight(request(session, buildRequest, assembly));

        assertThat(decision.compacted()).isFalse();
        assertThat(decision.context()).isSameAs(assembly.snapshot());
        assertThat(decision.reason()).contains("summary unavailable");
        assertThat(session.handle().byId().values())
            .filteredOn(CompactionEntry.class::isInstance)
            .isEmpty();
    }

    @Test
    void doesNotDependOnRebuildAfterAppendingCompactionEntry() {
        AgentCoreTestFixtures.InMemorySessionManager session = sessionWithLongBranch();
        DefaultContextAssembler assembler = lowBudgetAssembler(session);
        ContextBuildRequest buildRequest = buildRequest(session);
        ContextAssembly assembly = assembler.build(buildRequest);
        ContextAssembler failingRebuildAssembler = request -> {
            throw new IllegalStateException("rebuild should not run after append");
        };
        DefaultCompactionCoordinator coordinator = new DefaultCompactionCoordinator(
            session,
            failingRebuildAssembler,
            new AgentCoreTestFixtures.RecordingEventBus(),
            new DefaultCompactionPlanner(4),
            compactRequest -> new CompactSummaryResult("summary text", new TokenUsage(0, 0, 0, 0)),
            CLOCK
        );

        CompactionDecision decision = coordinator.preflight(request(session, buildRequest, assembly));

        assertThat(decision.compacted()).isTrue();
        assertThat(decision.context().messages())
            .extracting(AgentMessage::kind)
            .startsWith(MessageKind.SUMMARY);
        assertThat(session.handle().byId().values())
            .filteredOn(CompactionEntry.class::isInstance)
            .hasSize(1);
    }

    @Test
    void recordsTokensAfterFromCompactedContextInsteadOfSummaryUsage() {
        AgentCoreTestFixtures.InMemorySessionManager session = sessionWithLongBranch();
        DefaultContextAssembler assembler = lowBudgetAssembler(session);
        ContextBuildRequest buildRequest = buildRequest(session);
        ContextAssembly assembly = assembler.build(buildRequest);
        DefaultCompactionCoordinator coordinator = new DefaultCompactionCoordinator(
            session,
            assembler,
            new AgentCoreTestFixtures.RecordingEventBus(),
            new DefaultCompactionPlanner(4),
            compactRequest -> new CompactSummaryResult(
                "short summary",
                new TokenUsage(99_000, 1_000, 90_000, 0)
            ),
            CLOCK
        );

        CompactionDecision decision = coordinator.preflight(request(session, buildRequest, assembly));

        CompactionEntry entry = session.handle().byId().values().stream()
            .filter(CompactionEntry.class::isInstance)
            .map(CompactionEntry.class::cast)
            .findFirst()
            .orElseThrow();
        assertThat(entry.tokensBefore()).isEqualTo(assembly.snapshot().budget().estimatedContextTokens());
        assertThat(entry.tokensAfter()).isLessThan(99_000);
        assertThat(entry.tokensAfter()).isEqualTo(decision.context().budget().estimatedContextTokens());
        assertThat(decision.context().messages().getFirst().content().getFirst().text()).isEqualTo("short summary");
    }

    @Test
    void passesAbortSignalToSummarizerRequest() {
        AgentCoreTestFixtures.InMemorySessionManager session = sessionWithLongBranch();
        DefaultContextAssembler assembler = lowBudgetAssembler(session);
        ContextBuildRequest buildRequest = buildRequest(session);
        ContextAssembly assembly = assembler.build(buildRequest);
        AbortSignal abortSignal = () -> true;
        List<AbortSignal> captured = new java.util.ArrayList<>();
        DefaultCompactionCoordinator coordinator = new DefaultCompactionCoordinator(
            session,
            assembler,
            new AgentCoreTestFixtures.RecordingEventBus(),
            new DefaultCompactionPlanner(4),
            compactRequest -> {
                captured.add(compactRequest.abortSignal());
                return new CompactSummaryResult("summary text", new TokenUsage(0, 0, 0, 0));
            },
            CLOCK
        );

        coordinator.preflight(request(session, buildRequest, assembly, abortSignal));

        assertThat(captured).containsExactly(abortSignal);
    }

    @Test
    void tokensAfterIncludesProjectedBranchSummaryAndCustomMessages() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        session.openOrCreate("session-1");
        session.append(new MessageEntry("entry-user-1", "", userMessage("msg-user-1", "user one long enough to count"), NOW));
        session.append(new BranchSummaryEntry("entry-branch", "entry-user-1", "branch summary that must stay visible", NOW));
        session.append(new CustomMessageEntry("entry-custom", "entry-branch", "custom message that must stay visible", NOW));
        session.append(new MessageEntry("entry-user-2", "entry-custom", userMessage("msg-user-2", "kept user"), NOW));
        DefaultContextAssembler assembler = lowBudgetAssembler(session);
        ContextBuildRequest buildRequest = buildRequest(session);
        ContextAssembly assembly = assembler.build(buildRequest);
        DefaultCompactionCoordinator coordinator = new DefaultCompactionCoordinator(
            session,
            assembler,
            new AgentCoreTestFixtures.RecordingEventBus(),
            new DefaultCompactionPlanner(4),
            compactRequest -> new CompactSummaryResult("short summary", new TokenUsage(0, 0, 0, 0)),
            CLOCK
        );

        CompactionDecision decision = coordinator.preflight(request(session, buildRequest, assembly));

        assertThat(decision.compacted()).isTrue();
        assertThat(decision.context().messages())
            .extracting(message -> message.content().getFirst().text())
            .contains("short summary", "branch summary that must stay visible", "custom message that must stay visible");
        CompactionEntry entry = session.handle().byId().values().stream()
            .filter(CompactionEntry.class::isInstance)
            .map(CompactionEntry.class::cast)
            .findFirst()
            .orElseThrow();
        assertThat(entry.tokensAfter()).isEqualTo(decision.context().budget().estimatedContextTokens());
    }

    @Test
    void tokensAfterFallsBackToOriginalMessagesWhenFirstKeptEntryHasNoProjectedMessages() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        session.openOrCreate("session-1");
        session.append(new MessageEntry("entry-user-1", "", userMessage("msg-user-1", "original user text that remains in replay fallback"), NOW));
        session.append(new ModelChangeEntry(
            "entry-model",
            "entry-user-1",
            new cn.lypi.contracts.model.ModelSelection("test", "gpt-test-next", cn.lypi.contracts.model.ThinkingLevel.LOW),
            "test",
            NOW
        ));
        DefaultContextAssembler assembler = lowBudgetAssembler(session);
        ContextBuildRequest buildRequest = buildRequest(session);
        ContextAssembly assembly = assembler.build(buildRequest);
        DefaultCompactionCoordinator coordinator = new DefaultCompactionCoordinator(
            session,
            assembler,
            new AgentCoreTestFixtures.RecordingEventBus(),
            (branchEntries, context) -> Optional.of(new cn.lypi.contracts.session.CompactionPlan(
                "entry-user-1",
                "entry-model",
                List.of("entry-user-1"),
                cn.lypi.contracts.session.CompactionKind.SESSION
            )),
            compactRequest -> new CompactSummaryResult("short summary", new TokenUsage(0, 0, 0, 0)),
            CLOCK
        );

        CompactionDecision decision = coordinator.preflight(request(session, buildRequest, assembly));

        assertThat(decision.compacted()).isTrue();
        assertThat(decision.context().messages())
            .extracting(message -> message.content().getFirst().text())
            .contains("short summary", "original user text that remains in replay fallback");
        CompactionEntry entry = session.handle().byId().values().stream()
            .filter(CompactionEntry.class::isInstance)
            .map(CompactionEntry.class::cast)
            .findFirst()
            .orElseThrow();
        assertThat(entry.tokensAfter()).isEqualTo(decision.context().budget().estimatedContextTokens());
        assertThat(entry.tokensAfter())
            .isEqualTo(new ContextBudgetEstimator(64, 1, 8, 4)
                .estimate(decision.context().messages())
                .estimatedContextTokens());
    }

    private static AgentCoreTestFixtures.InMemorySessionManager sessionWithLongBranch() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        session.openOrCreate("session-1");
        session.append(new MessageEntry("entry-user-1", "", userMessage("msg-user-1", "user one long enough to count"), NOW));
        session.append(new MessageEntry("entry-assistant-1", "entry-user-1", assistantMessage("msg-assistant-1", "assistant one long enough"), NOW));
        session.append(new MessageEntry("entry-user-2", "entry-assistant-1", userMessage("msg-user-2", "user two long enough"), NOW));
        return session;
    }

    private static DefaultContextAssembler lowBudgetAssembler(AgentCoreTestFixtures.InMemorySessionManager session) {
        return new DefaultContextAssembler(
            session,
            fixedResourceRuntime("system"),
            new ContextBudgetEstimator(64, 1, 8, 4)
        );
    }

    private static ContextBuildRequest buildRequest(AgentCoreTestFixtures.InMemorySessionManager session) {
        return new ContextBuildRequest("session-1", Optional.of(session.leafId()), Path.of("."), true);
    }

    private static CompactionRequest request(
        AgentCoreTestFixtures.InMemorySessionManager session,
        ContextBuildRequest buildRequest,
        ContextAssembly assembly
    ) {
        return new CompactionRequest(
            "session-1",
            Optional.of(session.leafId()),
            Path.of("."),
            buildRequest,
            assembly,
            () -> false
        );
    }

    private static CompactionRequest request(
        AgentCoreTestFixtures.InMemorySessionManager session,
        ContextBuildRequest buildRequest,
        ContextAssembly assembly,
        AbortSignal abortSignal
    ) {
        return new CompactionRequest(
            "session-1",
            Optional.of(session.leafId()),
            Path.of("."),
            buildRequest,
            assembly,
            abortSignal
        );
    }
}
