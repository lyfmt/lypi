package cn.lypi.agent;

import cn.lypi.agent.compact.CompactionDecision;
import cn.lypi.agent.compact.CompactionRequest;
import cn.lypi.agent.compact.CompactionSummarizer;
import cn.lypi.agent.compact.CompactSummaryRequest;
import cn.lypi.agent.compact.CompactSummaryResult;
import cn.lypi.agent.compact.DefaultCompactionCoordinator;
import cn.lypi.agent.compact.DefaultCompactionPlanner;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.event.CompactEndEvent;
import cn.lypi.contracts.event.CompactStartEvent;
import cn.lypi.contracts.model.TokenUsage;
import cn.lypi.contracts.session.BranchSummaryEntry;
import cn.lypi.contracts.session.CompactionEntry;
import cn.lypi.contracts.session.CustomMessageEntry;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.SessionEntry;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
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
            request -> summaryResult("summary text"),
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
    void passesCurrentContextPlanAndBranchToSummarizer() {
        AgentCoreTestFixtures.InMemorySessionManager session = sessionWithLongBranch();
        DefaultContextAssembler assembler = lowBudgetAssembler(session);
        ContextBuildRequest buildRequest = buildRequest(session);
        ContextAssembly assembly = assembler.build(buildRequest);
        AtomicReference<CompactSummaryRequest> capturedRequest = new AtomicReference<>();
        DefaultCompactionCoordinator coordinator = new DefaultCompactionCoordinator(
            session,
            assembler,
            new AgentCoreTestFixtures.RecordingEventBus(),
            new DefaultCompactionPlanner(4),
            request -> {
                capturedRequest.set(request);
                return summaryResult("summary text");
            },
            CLOCK
        );

        CompactionDecision decision = coordinator.preflight(request(session, buildRequest, assembly));

        assertThat(decision.compacted()).isTrue();
        assertThat(capturedRequest.get()).isNotNull();
        assertThat(capturedRequest.get().context()).isSameAs(assembly.snapshot());
        assertThat(capturedRequest.get().context().messages()).containsExactlyElementsOf(assembly.snapshot().messages());
        assertThat(capturedRequest.get().plan()).isEqualTo(decision.plan().orElseThrow());
        assertThat(capturedRequest.get().branchEntries())
            .extracting(SessionEntry::id)
            .containsExactly("entry-user-1", "entry-assistant-1", "entry-user-2");
    }

    @Test
    void sendsProjectedCustomMessagesAndBranchSummariesToSummarizer() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        session.openOrCreate("session-1");
        session.append(new MessageEntry("entry-old", "", userMessage("msg-old", "Need compact with local context"), NOW));
        session.append(new CustomMessageEntry("entry-custom", "entry-old", "local instruction: preserve this", NOW));
        session.append(new BranchSummaryEntry("entry-branch", "entry-custom", "branch summary: preserve this too", NOW));
        session.append(new MessageEntry("entry-kept", "entry-branch", userMessage("msg-kept", "kept message long enough"), NOW));
        DefaultContextAssembler assembler = lowBudgetAssembler(session);
        ContextBuildRequest buildRequest = buildRequest(session);
        ContextAssembly assembly = assembler.build(buildRequest);
        AtomicReference<CompactSummaryRequest> capturedRequest = new AtomicReference<>();
        DefaultCompactionCoordinator coordinator = new DefaultCompactionCoordinator(
            session,
            assembler,
            new AgentCoreTestFixtures.RecordingEventBus(),
            new DefaultCompactionPlanner(4),
            request -> {
                capturedRequest.set(request);
                return summaryResult("summary text");
            },
            CLOCK
        );

        CompactionDecision decision = coordinator.preflight(request(session, buildRequest, assembly));

        assertThat(decision.compacted()).isTrue();
        assertThat(capturedRequest.get().context().messages())
            .extracting(message -> message.content().getFirst().text())
            .contains(
                "local instruction: preserve this",
                "branch summary: preserve this too"
            );
        assertThat(capturedRequest.get().context().messages())
            .extracting(AgentMessage::kind)
            .contains(MessageKind.TEXT, MessageKind.SUMMARY);
        assertThat(capturedRequest.get().context().messages())
            .extracting(AgentMessage::role)
            .contains(MessageRole.SYSTEM_LOCAL);
        assertThat(capturedRequest.get().plan().summarizedEntryIds())
            .contains("entry-custom", "entry-branch");
    }

    @Test
    void fallsBackToOriginalContextWhenSummarizerFails() {
        AgentCoreTestFixtures.InMemorySessionManager session = sessionWithLongBranch();
        DefaultContextAssembler assembler = lowBudgetAssembler(session);
        ContextBuildRequest buildRequest = buildRequest(session);
        ContextAssembly assembly = assembler.build(buildRequest);
        CompactionSummarizer failingSummarizer = request -> {
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
    void fallsBackToOriginalContextWhenSummarizerReturnsBlankSummary() {
        AgentCoreTestFixtures.InMemorySessionManager session = sessionWithLongBranch();
        DefaultContextAssembler assembler = new DefaultContextAssembler(
            session,
            fixedResourceRuntime("system prompt with many retained characters"),
            new ContextBudgetEstimator(128, 1, 8, 4)
        );
        ContextBuildRequest buildRequest = buildRequest(session);
        ContextAssembly assembly = assembler.build(buildRequest);
        DefaultCompactionCoordinator coordinator = new DefaultCompactionCoordinator(
            session,
            assembler,
            new AgentCoreTestFixtures.RecordingEventBus(),
            new DefaultCompactionPlanner(4),
            request -> summaryResult("   "),
            CLOCK
        );

        CompactionDecision decision = coordinator.preflight(request(session, buildRequest, assembly));

        assertThat(decision.compacted()).isFalse();
        assertThat(decision.context()).isSameAs(assembly.snapshot());
        assertThat(decision.reason()).contains("summary is empty");
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
            request -> summaryResult("summary text"),
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
    void returnsBudgetEstimatedFromSummaryAndKeptMessages() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        session.openOrCreate("session-1");
        session.append(new MessageEntry("entry-old", "", userMessage("msg-old", "old message long enough to compact"), NOW));
        session.append(new MessageEntry(
            "entry-kept",
            "entry-old",
            userMessage("msg-kept", "kept message has many many retained characters"),
            NOW
        ));
        DefaultContextAssembler assembler = lowBudgetAssembler(session);
        ContextBuildRequest buildRequest = buildRequest(session);
        ContextAssembly assembly = assembler.build(buildRequest);
        DefaultCompactionCoordinator coordinator = new DefaultCompactionCoordinator(
            session,
            assembler,
            new AgentCoreTestFixtures.RecordingEventBus(),
            new DefaultCompactionPlanner(4),
            request -> summaryResult("summary"),
            CLOCK
        );

        CompactionDecision decision = coordinator.preflight(request(session, buildRequest, assembly));

        int summaryOnlyTokens = Math.max(1, "summary".length() / 4);
        int expectedTokens = estimateTokens(decision.context());
        assertThat(decision.compacted()).isTrue();
        assertThat(decision.context().budget().estimatedContextTokens()).isEqualTo(expectedTokens);
        assertThat(decision.context().budget().estimatedContextTokens()).isGreaterThan(summaryOnlyTokens);
        assertThat(session.handle().byId().values())
            .filteredOn(CompactionEntry.class::isInstance)
            .singleElement()
            .extracting(entry -> ((CompactionEntry) entry).tokensAfter())
            .isEqualTo(expectedTokens);
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
            assembly
        );
    }

    private static CompactSummaryResult summaryResult(String summary) {
        return new CompactSummaryResult(summary, new TokenUsage(10, 2, 8, 0));
    }

    private static int estimateTokens(ContextSnapshot snapshot) {
        int systemTokens = snapshot.systemPrompt() == null ? 0 : estimateText(snapshot.systemPrompt().content());
        int messageTokens = snapshot.messages().stream()
            .flatMap(message -> message.content().stream())
            .mapToInt(DefaultCompactionCoordinatorTest::estimateBlock)
            .sum();
        return systemTokens + messageTokens;
    }

    private static int estimateBlock(ContentBlock block) {
        return estimateText(block.text());
    }

    private static int estimateText(String text) {
        String safeText = text == null ? "" : text;
        return Math.max(1, safeText.length() / 4);
    }
}
