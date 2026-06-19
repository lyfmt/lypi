package cn.lypi.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.agent.compact.CompactionCoordinator;
import cn.lypi.agent.compact.CompactionDecision;
import cn.lypi.agent.compact.CompactionSummarizer;
import cn.lypi.agent.compact.DefaultCompactionCoordinator;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.runtime.CompactionResult;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.session.CompactionKind;
import cn.lypi.contracts.session.CompactionEntry;
import cn.lypi.contracts.session.CompactionPlan;
import cn.lypi.contracts.session.MessageEntry;
import java.time.Clock;
import java.time.ZoneOffset;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DefaultCompactionRuntimeTest {
    @Test
    void compactBuildsContextAndDelegatesToCoordinator() {
        RecordingAssembler assembler = new RecordingAssembler();
        RecordingCoordinator coordinator = new RecordingCoordinator(new CompactionDecision(
            new ContextSnapshot(null, java.util.List.of(), null, null, null, PermissionMode.DEFAULT_EXECUTE, null),
            Optional.of(new CompactionPlan("entry-compact-1", "leaf_3", java.util.List.of("leaf_1"), CompactionKind.MANUAL)),
            true,
            "compacted",
            Optional.of("entry-compact-1")
        ));
        DefaultCompactionRuntime runtime = new DefaultCompactionRuntime(assembler, coordinator);

        CompactionResult result = runtime.compact(new cn.lypi.contracts.runtime.CompactionRequest(
            "ses_1",
            Optional.of("leaf_9"),
            Path.of("/tmp/project"),
            () -> false
        ));

        assertTrue(result.compacted());
        assertEquals(Optional.of("entry-compact-1"), result.entryId());
        assertEquals("compacted", result.message());
        assertEquals("ses_1", assembler.request.sessionId());
        assertEquals(Optional.of("leaf_9"), assembler.request.leafEntryId());
        assertEquals(Path.of("/tmp/project"), assembler.request.cwd());
        assertTrue(assembler.request.includeSystemPrompt());
        assertEquals(Optional.of("leaf_9"), coordinator.request.leafEntryId());
        assertEquals(assembler.assembly, coordinator.request.assembly());
    }

    @Test
    void compactReturnsNoopWhenCoordinatorFindsNoPlan() {
        DefaultCompactionRuntime runtime = new DefaultCompactionRuntime(
            new RecordingAssembler(),
            request -> new CompactionDecision(null, Optional.empty(), false, "within budget")
        );

        CompactionResult result = runtime.compact(new cn.lypi.contracts.runtime.CompactionRequest(
            "ses_1",
            Optional.empty(),
            Path.of("."),
            () -> false
        ));

        assertFalse(result.compacted());
        assertEquals("within budget", result.message());
        assertTrue(result.entryId().isEmpty());
    }

    @Test
    void manualCompactCreatesPlanEvenWhenContextIsWithinAutoBudget() {
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        session.openOrCreate("ses_1");
        session.append(new MessageEntry("entry-user-1", "", AgentCoreTestFixtures.userMessage("msg-user-1", "old user"), AgentCoreTestFixtures.NOW));
        session.append(new MessageEntry("entry-assistant-1", "entry-user-1", AgentCoreTestFixtures.assistantMessage("msg-assistant-1", "old assistant"), AgentCoreTestFixtures.NOW));
        session.append(new MessageEntry("entry-user-2", "entry-assistant-1", AgentCoreTestFixtures.userMessage("msg-user-2", "recent user"), AgentCoreTestFixtures.NOW));
        ContextAssembler assembler = request -> new ContextAssembly(
            new ContextSnapshot(
                null,
                List.of(
                    AgentCoreTestFixtures.userMessage("msg-user-1", "old user"),
                    AgentCoreTestFixtures.assistantMessage("msg-assistant-1", "old assistant"),
                    AgentCoreTestFixtures.userMessage("msg-user-2", "recent user")
                ),
                null,
                null,
                null,
                PermissionMode.DEFAULT_EXECUTE,
                new cn.lypi.contracts.context.ContextBudget(10, 128_000, 100_000, 8_192, 16_384, 0, 0, java.math.BigDecimal.ZERO)
            ),
            AgentCoreTestFixtures.emptyResources(),
            List.of("entry-user-1", "entry-assistant-1", "entry-user-2"),
            List.of(),
            List.of(),
            false
        );
        CompactionSummarizer summarizer = request -> new cn.lypi.agent.compact.CompactSummaryResult(
            "manual summary",
            new cn.lypi.contracts.model.TokenUsage(1, 1, 0, 2)
        );
        DefaultCompactionRuntime runtime = new DefaultCompactionRuntime(
            assembler,
            new DefaultCompactionCoordinator(
                session,
                assembler,
                new AgentCoreTestFixtures.RecordingEventBus(),
                DefaultCompactionRuntime.manualPlanner(),
                summarizer,
                Clock.fixed(AgentCoreTestFixtures.NOW, ZoneOffset.UTC)
            )
        );

        CompactionResult result = runtime.compact(new cn.lypi.contracts.runtime.CompactionRequest(
            "ses_1",
            Optional.of("entry-user-2"),
            Path.of("."),
            () -> false
        ));

        assertTrue(result.compacted());
        assertEquals("compacted", result.message());
        CompactionEntry entry = session.handle().byId().values().stream()
            .filter(CompactionEntry.class::isInstance)
            .map(CompactionEntry.class::cast)
            .findFirst()
            .orElseThrow();
        assertEquals(CompactionKind.MANUAL, entry.kind());
    }

    private static final class RecordingAssembler implements ContextAssembler {
        private ContextBuildRequest request;
        private final ContextAssembly assembly = new ContextAssembly(
            null,
            AgentCoreTestFixtures.emptyResources(),
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            false
        );

        @Override
        public ContextAssembly build(ContextBuildRequest request) {
            this.request = request;
            return assembly;
        }
    }

    private static final class RecordingCoordinator implements CompactionCoordinator {
        private final CompactionDecision decision;
        private cn.lypi.agent.compact.CompactionRequest request;

        private RecordingCoordinator(CompactionDecision decision) {
            this.decision = decision;
        }

        @Override
        public CompactionDecision preflight(cn.lypi.agent.compact.CompactionRequest request) {
            this.request = request;
            return decision;
        }
    }
}
