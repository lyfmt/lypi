package cn.lypi.agent;

import cn.lypi.agent.compact.CompactSummaryRequest;
import cn.lypi.agent.compact.CompactSummaryResult;
import cn.lypi.agent.compact.CompactionDecision;
import cn.lypi.agent.compact.CompactionRequest;
import cn.lypi.agent.compact.CompactionSummarizer;
import cn.lypi.agent.compact.DefaultCompactionCoordinator;
import cn.lypi.agent.compact.DefaultCompactionPlanner;
import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.AttachmentContentBlock;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.event.CompactEndEvent;
import cn.lypi.contracts.event.CompactStartEvent;
import cn.lypi.contracts.model.TokenUsage;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.resource.ContextFile;
import cn.lypi.contracts.resource.ResourceSnapshot;
import cn.lypi.contracts.session.BranchSummaryEntry;
import cn.lypi.contracts.session.CompactionEntry;
import cn.lypi.contracts.session.CustomMessageEntry;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.ModelChangeEntry;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.skill.SkillDescriptor;
import cn.lypi.contracts.skill.SkillIndex;
import cn.lypi.contracts.skill.SkillSource;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static cn.lypi.agent.AgentCoreTestFixtures.NOW;
import static cn.lypi.agent.AgentCoreTestFixtures.assistantMessage;
import static cn.lypi.agent.AgentCoreTestFixtures.assistantToolCallMessage;
import static cn.lypi.agent.AgentCoreTestFixtures.fixedResourceRuntime;
import static cn.lypi.agent.AgentCoreTestFixtures.toolResultMessage;
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
        assertThat(eventBus.events)
            .filteredOn(CompactEndEvent.class::isInstance)
            .singleElement()
            .extracting(event -> ((CompactEndEvent) event).compactionEntryId())
            .isEqualTo(session.handle().byId().values().stream()
                .filter(CompactionEntry.class::isInstance)
                .map(CompactionEntry.class::cast)
                .findFirst()
                .orElseThrow()
                .id());
    }

    @Test
    void passesCurrentContextPlanBranchAndAbortSignalToSummarizer() {
        AgentCoreTestFixtures.InMemorySessionManager session = sessionWithLongBranch();
        DefaultContextAssembler assembler = lowBudgetAssembler(session);
        ContextBuildRequest buildRequest = buildRequest(session);
        ContextAssembly assembly = assembler.build(buildRequest);
        AbortSignal abortSignal = () -> true;
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

        CompactionDecision decision = coordinator.preflight(request(session, buildRequest, assembly, abortSignal));

        assertThat(decision.compacted()).isTrue();
        assertThat(capturedRequest.get()).isNotNull();
        assertThat(capturedRequest.get().context()).isSameAs(assembly.snapshot());
        assertThat(capturedRequest.get().context().messages()).containsExactlyElementsOf(assembly.snapshot().messages());
        assertThat(capturedRequest.get().plan()).isEqualTo(decision.plan().orElseThrow());
        assertThat(capturedRequest.get().branchEntries())
            .extracting(SessionEntry::id)
            .containsExactly("entry-user-1", "entry-assistant-1", "entry-user-2");
        assertThat(capturedRequest.get().abortSignal()).isSameAs(abortSignal);
    }

    @Test
    void sendsProjectedCustomMessagesAndBranchSummariesToSummarizer() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        session.openOrCreate("session-1");
        session.append(new MessageEntry("entry-old", "", userMessage("msg-old", "Need compact with local context"), NOW));
        session.append(new CustomMessageEntry("entry-custom", "entry-old", "local instruction: preserve this", NOW));
        session.append(new BranchSummaryEntry("entry-branch", "entry-custom", "entry-old-leaf", "branch summary: preserve this too", NOW));
        session.append(new MessageEntry("entry-assistant-old", "entry-branch", assistantMessage("msg-assistant-old", "old assistant round"), NOW));
        session.append(new MessageEntry("entry-kept", "entry-assistant-old", assistantMessage("msg-kept", "kept assistant round long enough"), NOW));
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
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        CompactionSummarizer failingSummarizer = request -> {
            throw new IllegalStateException("summary unavailable");
        };
        DefaultCompactionCoordinator coordinator = new DefaultCompactionCoordinator(
            session,
            assembler,
            eventBus,
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
        assertThat(eventBus.events)
            .extracting(Object::getClass)
            .containsSubsequence(CompactStartEvent.class, CompactEndEvent.class);
        assertThat(eventBus.events)
            .filteredOn(CompactEndEvent.class::isInstance)
            .singleElement()
            .extracting(event -> ((CompactEndEvent) event).compactionEntryId())
            .isEqualTo("");
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
            request -> null,
            CLOCK
        );

        CompactionDecision decision = coordinator.preflight(request(session, buildRequest, assembly));

        assertThat(decision.compacted()).isFalse();
        assertThat(decision.context()).isSameAs(assembly.snapshot());
        assertThat(decision.reason()).contains("summary");
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
    void compactedDecisionDoesNotReplaySessionContextAfterAppendingCompactionEntry() {
        ContextFailingAfterCompactionAppendSessionManager session = new ContextFailingAfterCompactionAppendSessionManager();
        session.openOrCreate("session-1");
        session.append(new MessageEntry("entry-user-1", "", userMessage("msg-user-1", "user one long enough to count"), NOW));
        session.append(new MessageEntry("entry-assistant-1", "entry-user-1", assistantMessage("msg-assistant-1", "assistant one long enough"), NOW));
        session.append(new MessageEntry("entry-user-2", "entry-assistant-1", userMessage("msg-user-2", "user two long enough"), NOW));
        DefaultContextAssembler assembler = lowBudgetAssembler(session);
        ContextBuildRequest buildRequest = buildRequest(session);
        ContextAssembly assembly = assembler.build(buildRequest);
        DefaultCompactionCoordinator coordinator = new DefaultCompactionCoordinator(
            session,
            assembler,
            new AgentCoreTestFixtures.RecordingEventBus(),
            new DefaultCompactionPlanner(4),
            request -> summaryResult("summary text"),
            CLOCK
        );

        CompactionDecision decision = coordinator.preflight(request(session, buildRequest, assembly));

        assertThat(decision.compacted()).isTrue();
        assertThat(decision.context().messages())
            .extracting(message -> message.content().getFirst().text())
            .contains("summary text", "assistant one long enough", "user two long enough");
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
            request -> new CompactSummaryResult(
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
    void returnsBudgetEstimatedFromSummaryAndKeptMessages() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        session.openOrCreate("session-1");
        session.append(new MessageEntry("entry-old", "", userMessage("msg-old", "old message long enough to compact"), NOW));
        session.append(new MessageEntry("entry-assistant-old", "entry-old", assistantMessage("msg-assistant-old", "old assistant round"), NOW));
        session.append(new MessageEntry(
            "entry-kept",
            "entry-assistant-old",
            assistantMessage("msg-kept", "kept message has many many retained characters"),
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

    @Test
    void tokensAfterIncludesProjectedBranchSummaryAndCustomMessages() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        session.openOrCreate("session-1");
        session.append(new MessageEntry("entry-user-1", "", userMessage("msg-user-1", "user one long enough to count"), NOW));
        session.append(new MessageEntry("entry-assistant-1", "entry-user-1", assistantMessage("msg-assistant-1", "old assistant round"), NOW));
        session.append(new BranchSummaryEntry("entry-branch", "entry-assistant-1", "entry-old-leaf", "branch summary that must stay visible", NOW));
        session.append(new CustomMessageEntry("entry-custom", "entry-branch", "custom message that must stay visible", NOW));
        session.append(new MessageEntry("entry-user-2", "entry-custom", userMessage("msg-user-2", "kept user"), NOW));
        session.append(new MessageEntry("entry-assistant-2", "entry-user-2", assistantMessage("msg-assistant-2", "kept assistant"), NOW));
        DefaultContextAssembler assembler = lowBudgetAssembler(session);
        ContextBuildRequest buildRequest = buildRequest(session);
        ContextAssembly assembly = assembler.build(buildRequest);
        DefaultCompactionCoordinator coordinator = new DefaultCompactionCoordinator(
            session,
            assembler,
            new AgentCoreTestFixtures.RecordingEventBus(),
            new DefaultCompactionPlanner(4),
            request -> summaryResult("short summary"),
            CLOCK
        );

        CompactionDecision decision = coordinator.preflight(request(session, buildRequest, assembly));

        assertThat(decision.compacted()).isTrue();
        assertThat(decision.context().messages())
            .extracting(message -> message.content().getFirst().text())
            .contains("short summary", "branch summary that must stay visible", "custom message that must stay visible");
        assertThat(decision.context().budget().estimatedContextTokens()).isEqualTo(estimateTokens(decision.context()));
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
            request -> summaryResult("short summary"),
            CLOCK
        );

        CompactionDecision decision = coordinator.preflight(request(session, buildRequest, assembly));

        assertThat(decision.compacted()).isTrue();
        assertThat(decision.context().messages())
            .extracting(message -> message.content().getFirst().text())
            .contains("short summary", "original user text that remains in replay fallback");
        assertThat(decision.context().budget().estimatedContextTokens()).isEqualTo(estimateTokens(decision.context()));
    }

    @Test
    void appendsReadStateBackfillFromDroppedSuccessfulReadResults() {
        AgentCoreTestFixtures.InMemorySessionManager session = sessionWithReadStateBranch();
        DefaultContextAssembler assembler = resourceAssembler(session);
        ContextBuildRequest buildRequest = buildRequest(session);
        ContextAssembly assembly = assembler.build(buildRequest);
        DefaultCompactionCoordinator coordinator = new DefaultCompactionCoordinator(
            session,
            assembler,
            new AgentCoreTestFixtures.RecordingEventBus(),
            readStatePlan(),
            request -> summaryResult("summary text"),
            CLOCK
        );

        CompactionDecision decision = coordinator.preflight(request(session, buildRequest, assembly));

        assertThat(decision.compacted()).isTrue();
        assertThat(session.handle().byId().values())
            .filteredOn(MessageEntry.class::isInstance)
            .map(MessageEntry.class::cast)
            .map(MessageEntry::message)
            .anySatisfy(message -> {
                assertThat(message.role()).isEqualTo(MessageRole.SYSTEM_LOCAL);
                assertThat(message.kind()).isEqualTo(MessageKind.ATTACHMENT);
                assertThat(message.content()).singleElement()
                    .isInstanceOfSatisfying(AttachmentContentBlock.class, attachment -> {
                        assertThat(attachment.attachmentId()).startsWith("compact-resource-");
                        assertThat(attachment.text())
                            .contains("src/Dropped.java")
                            .contains("dropped read content")
                            .doesNotContain("context instructions that must not be restored from ResourceSnapshot")
                            .doesNotContain("skill:compact-helper")
                            .doesNotContain("AGENTS.md")
                            .doesNotContain("kept read content");
                    });
            });
        assertThat(decision.context().messages())
            .anySatisfy(message -> assertThat(message.content())
                .anySatisfy(block -> assertThat(block.text()).contains("dropped read content")));
        CompactionEntry compactionEntry = session.handle().byId().values().stream()
            .filter(CompactionEntry.class::isInstance)
            .map(CompactionEntry.class::cast)
            .findFirst()
            .orElseThrow();
        assertThat(session.handle().byId().values())
            .filteredOn(CompactionEntry.class::isInstance)
            .hasSize(1);
        assertThat(compactionEntry.tokensAfter())
            .isEqualTo(decision.context().budget().estimatedContextTokens())
            .isEqualTo(estimateTokens(assembly.snapshot(), session.context(session.leafId()).messages()));
        assertThat(decision.context().budget().estimatedContextTokens())
            .isEqualTo(estimateTokens(decision.context()));
    }

    @Test
    void resourceBackfillDecisionContextMatchesReplayWithLiveClock() {
        AgentCoreTestFixtures.InMemorySessionManager session = sessionWithReadStateBranch();
        DefaultContextAssembler assembler = resourceAssembler(session);
        ContextBuildRequest buildRequest = buildRequest(session);
        ContextAssembly assembly = assembler.build(buildRequest);
        DefaultCompactionCoordinator coordinator = new DefaultCompactionCoordinator(
            session,
            assembler,
            new AgentCoreTestFixtures.RecordingEventBus(),
            readStatePlan(),
            request -> summaryResult("summary text"),
            Clock.systemUTC()
        );

        CompactionDecision decision = coordinator.preflight(request(session, buildRequest, assembly));

        assertThat(decision.compacted()).isTrue();
        assertThat(session.context(session.leafId()).messages()).containsExactlyElementsOf(decision.context().messages());
    }

    @Test
    void readStateBackfillExcludesDroppedReadsAlreadyPresentInKeptTail() {
        AgentCoreTestFixtures.InMemorySessionManager session = sessionWithReadStateBranchReadingSamePathInTail();
        DefaultContextAssembler assembler = resourceAssembler(session);
        ContextBuildRequest buildRequest = buildRequest(session);
        ContextAssembly assembly = assembler.build(buildRequest);
        DefaultCompactionCoordinator coordinator = new DefaultCompactionCoordinator(
            session,
            assembler,
            new AgentCoreTestFixtures.RecordingEventBus(),
            readStatePlan(),
            request -> summaryResult("summary after compact"),
            CLOCK
        );

        CompactionDecision decision = coordinator.preflight(request(session, buildRequest, assembly));

        assertThat(decision.compacted()).isTrue();
        assertThat(session.handle().byId().values())
            .filteredOn(MessageEntry.class::isInstance)
            .map(MessageEntry.class::cast)
            .map(MessageEntry::message)
            .filteredOn(message -> message.role() == MessageRole.SYSTEM_LOCAL)
            .filteredOn(message -> message.kind() == MessageKind.ATTACHMENT)
            .isEmpty();
        assertThat(decision.context().messages())
            .flatExtracting(AgentMessage::content)
            .extracting(ContentBlock::text)
            .doesNotContain("File: src/Dropped.java\n1 | dropped read content");
    }

    @Test
    void readStateBackfillKeepsDroppedReadWhenKeptTailReadOfSamePathFailed() {
        AgentCoreTestFixtures.InMemorySessionManager session = sessionWithReadStateBranchReadingSamePathInTailWithError();
        DefaultContextAssembler assembler = resourceAssembler(session);
        ContextBuildRequest buildRequest = buildRequest(session);
        ContextAssembly assembly = assembler.build(buildRequest);
        DefaultCompactionCoordinator coordinator = new DefaultCompactionCoordinator(
            session,
            assembler,
            new AgentCoreTestFixtures.RecordingEventBus(),
            readStatePlan(),
            request -> summaryResult("summary after compact"),
            CLOCK
        );

        CompactionDecision decision = coordinator.preflight(request(session, buildRequest, assembly));

        assertThat(decision.compacted()).isTrue();
        assertThat(resourceBackfillText(session))
            .contains("src/Dropped.java")
            .contains("dropped read content")
            .doesNotContain("kept failed read");
    }

    @Test
    void readStateBackfillExcludesRelativeNestedAndWindowsSystemResourcePaths() {
        AgentCoreTestFixtures.InMemorySessionManager session = sessionWithSystemResourceReadOnlyBranch();
        DefaultContextAssembler assembler = resourceAssembler(session);
        ContextBuildRequest buildRequest = buildRequest(session);
        ContextAssembly assembly = assembler.build(buildRequest);
        DefaultCompactionCoordinator coordinator = new DefaultCompactionCoordinator(
            session,
            assembler,
            new AgentCoreTestFixtures.RecordingEventBus(),
            readStatePlan(),
            request -> summaryResult("summary after compact"),
            CLOCK
        );

        CompactionDecision decision = coordinator.preflight(request(session, buildRequest, assembly));

        assertThat(decision.compacted()).isTrue();
        assertThat(session.handle().byId().values())
            .filteredOn(MessageEntry.class::isInstance)
            .map(MessageEntry.class::cast)
            .map(MessageEntry::message)
            .filteredOn(message -> message.role() == MessageRole.SYSTEM_LOCAL)
            .filteredOn(message -> message.kind() == MessageKind.ATTACHMENT)
            .isEmpty();
        assertThat(decision.context().messages())
            .flatExtracting(AgentMessage::content)
            .extracting(ContentBlock::text)
            .doesNotContain("skill instructions")
            .doesNotContain("codex instructions")
            .doesNotContain("claude instructions")
            .doesNotContain("nested codex instructions")
            .doesNotContain("windows claude instructions");
    }

    @Test
    void readStateBackfillDoesNotRestoreSystemResourceSnapshotWhenNoReadStateWasDropped() {
        AgentCoreTestFixtures.InMemorySessionManager session = sessionWithLongBranch();
        DefaultContextAssembler assembler = resourceAssembler(session);
        ContextBuildRequest buildRequest = buildRequest(session);
        ContextAssembly assembly = assembler.build(buildRequest);
        DefaultCompactionCoordinator coordinator = new DefaultCompactionCoordinator(
            session,
            assembler,
            new AgentCoreTestFixtures.RecordingEventBus(),
            new DefaultCompactionPlanner(4),
            request -> summaryResult("summary after compact"),
            CLOCK
        );

        CompactionDecision decision = coordinator.preflight(request(session, buildRequest, assembly));

        assertThat(decision.compacted()).isTrue();
        assertThat(session.handle().byId().values())
            .filteredOn(MessageEntry.class::isInstance)
            .map(MessageEntry.class::cast)
            .map(MessageEntry::message)
            .filteredOn(message -> message.role() == MessageRole.SYSTEM_LOCAL)
            .filteredOn(message -> message.kind() == MessageKind.ATTACHMENT)
            .isEmpty();
        assertThat(decision.context().messages())
            .flatExtracting(AgentMessage::content)
            .extracting(ContentBlock::text)
            .doesNotContain("context instructions that must not be restored from ResourceSnapshot")
            .doesNotContain("compact-restored prompt body")
            .doesNotContain("skill:compact-helper");
    }

    @Test
    void readStateBackfillTruncationNoticeStaysWithinAttachmentLimit() {
        AgentCoreTestFixtures.InMemorySessionManager session = sessionWithLargeReadStateBranch();
        DefaultContextAssembler assembler = resourceAssembler(session);
        ContextBuildRequest buildRequest = buildRequest(session);
        ContextAssembly assembly = assembler.build(buildRequest);
        DefaultCompactionCoordinator coordinator = new DefaultCompactionCoordinator(
            session,
            assembler,
            new AgentCoreTestFixtures.RecordingEventBus(),
            readStatePlan(),
            request -> summaryResult("summary after compact"),
            CLOCK
        );

        CompactionDecision decision = coordinator.preflight(request(session, buildRequest, assembly));

        String text = resourceBackfillText(session);
        assertThat(decision.compacted()).isTrue();
        assertThat(text).contains("内容已截断");
        assertThat(text).hasSizeLessThanOrEqualTo(20_000);
    }

    @Test
    void resourceBackfillAppendFailureDoesNotReturnOriginalContextAfterCompactionAppend() {
        BackfillFailingAfterCompactionAppendSessionManager session = new BackfillFailingAfterCompactionAppendSessionManager();
        populateReadStateBranch(session, false, false);
        DefaultContextAssembler assembler = resourceAssembler(session);
        ContextBuildRequest buildRequest = buildRequest(session);
        ContextAssembly assembly = assembler.build(buildRequest);
        DefaultCompactionCoordinator coordinator = new DefaultCompactionCoordinator(
            session,
            assembler,
            new AgentCoreTestFixtures.RecordingEventBus(),
            readStatePlan(),
            request -> summaryResult("summary text"),
            CLOCK
        );

        CompactionDecision decision = coordinator.preflight(request(session, buildRequest, assembly));

        assertThat(decision.compacted()).isTrue();
        assertThat(decision.reason()).contains("resource backfill failed: backfill append failed");
        assertThat(decision.context()).isNotSameAs(assembly.snapshot());
        assertThat(decision.context().messages())
            .extracting(message -> message.content().getFirst().text())
            .contains("summary text", "File: src/Kept.java\n1 | kept read content", "current user request")
            .doesNotContain("File: src/Dropped.java\n1 | dropped read content")
            .doesNotContain("context instructions that must not be restored from ResourceSnapshot");
        assertThat(session.context(session.leafId()).messages()).containsExactlyElementsOf(decision.context().messages());
        assertThat(session.handle().byId().values())
            .filteredOn(CompactionEntry.class::isInstance)
            .hasSize(1);
        assertThat(session.entry(session.leafId()))
            .isInstanceOfSatisfying(CompactionEntry.class, entry ->
                assertThat(entry.tokensAfter()).isGreaterThan(decision.context().budget().estimatedContextTokens())
            );
        assertThat(session.handle().byId().values())
            .filteredOn(MessageEntry.class::isInstance)
            .map(MessageEntry.class::cast)
            .map(MessageEntry::message)
            .filteredOn(message -> message.role() == MessageRole.SYSTEM_LOCAL)
            .filteredOn(message -> message.kind() == MessageKind.ATTACHMENT)
            .isEmpty();
    }

    private static cn.lypi.agent.compact.CompactionPlanner readStatePlan() {
        return (branchEntries, context) -> Optional.of(new cn.lypi.contracts.session.CompactionPlan(
            "entry-tool-agents",
            "entry-assistant-kept",
            List.of(
                "entry-user-1",
                "entry-assistant-dropped",
                "entry-tool-dropped",
                "entry-assistant-codex",
                "entry-tool-codex",
                "entry-assistant-claude",
                "entry-tool-claude",
                "entry-assistant-nested-codex",
                "entry-tool-nested-codex",
                "entry-assistant-windows-claude",
                "entry-tool-windows-claude",
                "entry-assistant-agents",
                "entry-tool-agents"
            ),
            cn.lypi.contracts.session.CompactionKind.SESSION
        ));
    }

    private static AgentCoreTestFixtures.InMemorySessionManager sessionWithReadStateBranch() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        populateReadStateBranch(session, false, false);
        return session;
    }

    private static AgentCoreTestFixtures.InMemorySessionManager sessionWithReadStateBranchReadingSamePathInTail() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        populateReadStateBranch(session, true, false);
        return session;
    }

    private static AgentCoreTestFixtures.InMemorySessionManager sessionWithReadStateBranchReadingSamePathInTailWithError() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        populateReadStateBranch(session, true, false, true, false);
        return session;
    }

    private static AgentCoreTestFixtures.InMemorySessionManager sessionWithLargeReadStateBranch() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        populateReadStateBranch(session, false, true);
        return session;
    }

    private static AgentCoreTestFixtures.InMemorySessionManager sessionWithSystemResourceReadOnlyBranch() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        populateReadStateBranch(session, false, false, false, true);
        return session;
    }

    private static void populateReadStateBranch(
        AgentCoreTestFixtures.InMemorySessionManager session,
        boolean keptTailReadsDroppedPath,
        boolean largeDroppedRead
    ) {
        populateReadStateBranch(session, keptTailReadsDroppedPath, largeDroppedRead, false, false);
    }

    private static void populateReadStateBranch(
        AgentCoreTestFixtures.InMemorySessionManager session,
        boolean keptTailReadsDroppedPath,
        boolean largeDroppedRead,
        boolean keptTailReadError,
        boolean systemResourceOnly
    ) {
        session.openOrCreate("session-1");
        session.append(new MessageEntry("entry-user-1", "", userMessage("msg-user-1", "user one long enough to count"), NOW));
        String droppedPath = systemResourceOnly ? ".ly-pi/skills/helper/SKILL.md" : "src/Dropped.java";
        session.append(new MessageEntry(
            "entry-assistant-dropped",
            "entry-user-1",
            assistantToolCallMessage("msg-assistant-dropped", "tool-dropped", "read", java.util.Map.of("path", droppedPath)),
            NOW
        ));
        String droppedReadText;
        if (systemResourceOnly) {
            droppedReadText = "File: .ly-pi/skills/helper/SKILL.md\n1 | skill instructions";
        } else if (largeDroppedRead) {
            droppedReadText = "File: src/Dropped.java\n" + "1 | " + "A".repeat(30_000);
        } else {
            droppedReadText = "File: src/Dropped.java\n1 | dropped read content";
        }
        session.append(new MessageEntry(
            "entry-tool-dropped",
            "entry-assistant-dropped",
            toolResultMessage("msg-tool-dropped", "tool-dropped", droppedReadText, false),
            NOW
        ));
        if (systemResourceOnly) {
            session.append(new MessageEntry(
                "entry-assistant-codex",
                "entry-tool-dropped",
                assistantToolCallMessage("msg-assistant-codex", "tool-codex", "read", java.util.Map.of("path", ".codex/rules.md")),
                NOW
            ));
            session.append(new MessageEntry(
                "entry-tool-codex",
                "entry-assistant-codex",
                toolResultMessage("msg-tool-codex", "tool-codex", "File: .codex/rules.md\n1 | codex instructions", false),
                NOW
            ));
            session.append(new MessageEntry(
                "entry-assistant-claude",
                "entry-tool-codex",
                assistantToolCallMessage("msg-assistant-claude", "tool-claude", "read", java.util.Map.of("path", ".claude/settings.md")),
                NOW
            ));
            session.append(new MessageEntry(
                "entry-tool-claude",
                "entry-assistant-claude",
                toolResultMessage("msg-tool-claude", "tool-claude", "File: .claude/settings.md\n1 | claude instructions", false),
                NOW
            ));
            session.append(new MessageEntry(
                "entry-assistant-nested-codex",
                "entry-tool-claude",
                assistantToolCallMessage(
                    "msg-assistant-nested-codex",
                    "tool-nested-codex",
                    "read",
                    java.util.Map.of("path", "src/module/.codex/config.md")
                ),
                NOW
            ));
            session.append(new MessageEntry(
                "entry-tool-nested-codex",
                "entry-assistant-nested-codex",
                toolResultMessage(
                    "msg-tool-nested-codex",
                    "tool-nested-codex",
                    "File: src/module/.codex/config.md\n1 | nested codex instructions",
                    false
                ),
                NOW
            ));
            session.append(new MessageEntry(
                "entry-assistant-windows-claude",
                "entry-tool-nested-codex",
                assistantToolCallMessage(
                    "msg-assistant-windows-claude",
                    "tool-windows-claude",
                    "read",
                    java.util.Map.of("path", "C:\\repo\\.claude\\settings.md")
                ),
                NOW
            ));
            session.append(new MessageEntry(
                "entry-tool-windows-claude",
                "entry-assistant-windows-claude",
                toolResultMessage(
                    "msg-tool-windows-claude",
                    "tool-windows-claude",
                    "File: C:\\repo\\.claude\\settings.md\n1 | windows claude instructions",
                    false
                ),
                NOW
            ));
        }
        session.append(new MessageEntry(
            "entry-assistant-agents",
            systemResourceOnly ? "entry-tool-windows-claude" : "entry-tool-dropped",
            assistantToolCallMessage("msg-assistant-agents", "tool-agents", "read", java.util.Map.of("path", "AGENTS.md")),
            NOW
        ));
        session.append(new MessageEntry(
            "entry-tool-agents",
            "entry-assistant-agents",
            toolResultMessage("msg-tool-agents", "tool-agents", "File: AGENTS.md\n1 | system instructions", false),
            NOW
        ));
        String keptPath = keptTailReadsDroppedPath ? "src/Dropped.java" : "src/Kept.java";
        String keptText = keptTailReadsDroppedPath
            ? "File: src/Dropped.java\n1 | kept replacement read content"
            : "File: src/Kept.java\n1 | kept read content";
        session.append(new MessageEntry(
            "entry-assistant-kept",
            "entry-tool-agents",
            assistantToolCallMessage("msg-assistant-kept", "tool-kept", "read", java.util.Map.of("path", keptPath)),
            NOW
        ));
        session.append(new MessageEntry(
            "entry-tool-kept",
            "entry-assistant-kept",
            toolResultMessage("msg-tool-kept", "tool-kept", keptTailReadError ? "kept failed read" : keptText, keptTailReadError),
            NOW
        ));
        session.append(new MessageEntry(
            "entry-user-2",
            "entry-tool-kept",
            userMessage("msg-user-2", "current user request"),
            NOW
        ));
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

    private static DefaultContextAssembler resourceAssembler(AgentCoreTestFixtures.InMemorySessionManager session) {
        return resourceAssembler(
            session,
            List.of(new ContextFile(
                Path.of("AGENTS.md"),
                "context instructions that must not be restored from ResourceSnapshot",
                "sha256:agents"
            )),
            new SkillIndex(List.of(new SkillDescriptor(
                "compact-helper",
                "Helps restore context after compact",
                SkillSource.PROJECT,
                Path.of(".ly-pi/skills/compact-helper/SKILL.md"),
                List.of("**/*.java"),
                List.of("read"),
                "sha256:skill"
            )), List.of())
        );
    }

    private static DefaultContextAssembler resourceAssembler(
        AgentCoreTestFixtures.InMemorySessionManager session,
        List<ContextFile> contextFiles,
        SkillIndex skillIndex
    ) {
        ResourceSnapshot resources = new ResourceSnapshot(
            contextFiles,
            List.of(),
            skillIndex,
            List.of(),
            List.of(),
            List.of()
        );
        return new DefaultContextAssembler(
            session,
            new cn.lypi.contracts.runtime.ResourceRuntimePort() {
                @Override
                public ResourceSnapshot load(Path cwd) {
                    return resources;
                }

                @Override
                public SystemPrompt buildSystemPrompt(ResourceSnapshot ignored) {
                    return new SystemPrompt("system", List.of("test"), "hash");
                }
            },
            new ContextBudgetEstimator(512, 1, 8, 4)
        );
    }

    private static String resourceBackfillText(AgentCoreTestFixtures.InMemorySessionManager session) {
        return session.handle().byId().values().stream()
            .filter(MessageEntry.class::isInstance)
            .map(MessageEntry.class::cast)
            .map(MessageEntry::message)
            .filter(message -> message.role() == MessageRole.SYSTEM_LOCAL)
            .filter(message -> message.kind() == MessageKind.ATTACHMENT)
            .flatMap(message -> message.content().stream())
            .findFirst()
            .orElseThrow()
            .text();
    }

    private static ContextBuildRequest buildRequest(AgentCoreTestFixtures.InMemorySessionManager session) {
        return new ContextBuildRequest("session-1", Optional.of(session.leafId()), Path.of("."), true);
    }

    private static CompactionRequest request(
        AgentCoreTestFixtures.InMemorySessionManager session,
        ContextBuildRequest buildRequest,
        ContextAssembly assembly
    ) {
        return request(session, buildRequest, assembly, () -> false);
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

    private static CompactSummaryResult summaryResult(String summary) {
        return new CompactSummaryResult(summary, new TokenUsage(10, 2, 8, 0));
    }

    private static int estimateTokens(ContextSnapshot snapshot) {
        int systemTokens = snapshot.systemPrompt() == null ? 0 : estimateText(snapshot.systemPrompt().content());
        int messageTokens = estimateMessagesTokens(snapshot.messages());
        return systemTokens + messageTokens;
    }

    private static int estimateTokens(ContextSnapshot snapshot, List<AgentMessage> messages) {
        int systemTokens = snapshot.systemPrompt() == null ? 0 : estimateText(snapshot.systemPrompt().content());
        int messageTokens = estimateMessagesTokens(messages);
        return systemTokens + messageTokens;
    }

    private static int estimateMessagesTokens(List<AgentMessage> messages) {
        return messages.stream()
            .flatMap(message -> message.content().stream())
            .mapToInt(DefaultCompactionCoordinatorTest::estimateBlock)
            .sum();
    }

    private static int estimateBlock(ContentBlock block) {
        return estimateText(block.text());
    }

    private static int estimateText(String text) {
        String safeText = text == null ? "" : text;
        return Math.max(1, safeText.length() / 4);
    }

    private static final class ContextFailingAfterCompactionAppendSessionManager
        extends AgentCoreTestFixtures.InMemorySessionManager {
        private boolean compactionAppended;

        @Override
        public cn.lypi.contracts.session.SessionHandle append(SessionEntry entry) {
            cn.lypi.contracts.session.SessionHandle handle = super.append(entry);
            if (entry instanceof CompactionEntry) {
                compactionAppended = true;
            }
            return handle;
        }

        @Override
        public cn.lypi.contracts.session.SessionContext context(String leafId) {
            if (compactionAppended) {
                throw new IllegalStateException("context replay should not run after compaction append");
            }
            return super.context(leafId);
        }
    }

    private static final class BackfillFailingAfterCompactionAppendSessionManager
        extends AgentCoreTestFixtures.InMemorySessionManager {
        private boolean compactionAppended;

        @Override
        public cn.lypi.contracts.session.SessionHandle append(SessionEntry entry) {
            if (compactionAppended && entry instanceof MessageEntry messageEntry
                && messageEntry.message().role() == MessageRole.SYSTEM_LOCAL
                && messageEntry.message().kind() == MessageKind.ATTACHMENT) {
                throw new IllegalStateException("backfill append failed");
            }
            cn.lypi.contracts.session.SessionHandle handle = super.append(entry);
            if (entry instanceof CompactionEntry) {
                compactionAppended = true;
            }
            return handle;
        }
    }
}
