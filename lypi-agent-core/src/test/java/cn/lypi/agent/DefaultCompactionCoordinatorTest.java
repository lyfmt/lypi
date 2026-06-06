package cn.lypi.agent;

import cn.lypi.agent.compact.CompactionDecision;
import cn.lypi.agent.compact.CompactionRequest;
import cn.lypi.agent.compact.CompactionSummarizer;
import cn.lypi.agent.compact.DefaultCompactionCoordinator;
import cn.lypi.agent.compact.DefaultCompactionPlanner;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.event.CompactEndEvent;
import cn.lypi.contracts.event.CompactStartEvent;
import cn.lypi.contracts.session.CompactionEntry;
import cn.lypi.contracts.session.MessageEntry;
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
        AgentCoreTestFixtures.InMemorySessionEngine session = sessionWithLongBranch();
        DefaultContextAssembler assembler = lowBudgetAssembler(session);
        ContextBuildRequest buildRequest = buildRequest(session);
        ContextAssembly assembly = assembler.build(buildRequest);
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        DefaultCompactionCoordinator coordinator = new DefaultCompactionCoordinator(
            session,
            assembler,
            eventBus,
            new DefaultCompactionPlanner(4),
            (branchEntries, plan, context) -> "summary text",
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
        AgentCoreTestFixtures.InMemorySessionEngine session = sessionWithLongBranch();
        DefaultContextAssembler assembler = lowBudgetAssembler(session);
        ContextBuildRequest buildRequest = buildRequest(session);
        ContextAssembly assembly = assembler.build(buildRequest);
        CompactionSummarizer failingSummarizer = (branchEntries, plan, context) -> {
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

    private static AgentCoreTestFixtures.InMemorySessionEngine sessionWithLongBranch() {
        AgentCoreTestFixtures.InMemorySessionEngine session = new AgentCoreTestFixtures.InMemorySessionEngine();
        session.openOrCreate("session-1");
        session.append(new MessageEntry("entry-user-1", "", userMessage("msg-user-1", "user one long enough to count"), NOW));
        session.append(new MessageEntry("entry-assistant-1", "entry-user-1", assistantMessage("msg-assistant-1", "assistant one long enough"), NOW));
        session.append(new MessageEntry("entry-user-2", "entry-assistant-1", userMessage("msg-user-2", "user two long enough"), NOW));
        return session;
    }

    private static DefaultContextAssembler lowBudgetAssembler(AgentCoreTestFixtures.InMemorySessionEngine session) {
        return new DefaultContextAssembler(
            session,
            fixedResourceRuntime("system"),
            new ContextBudgetEstimator(64, 1, 8, 4)
        );
    }

    private static ContextBuildRequest buildRequest(AgentCoreTestFixtures.InMemorySessionEngine session) {
        return new ContextBuildRequest("session-1", Optional.of(session.leafId()), Path.of("."), true);
    }

    private static CompactionRequest request(
        AgentCoreTestFixtures.InMemorySessionEngine session,
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
}
