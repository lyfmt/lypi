package cn.lypi.agent;

import cn.lypi.contracts.agent.TurnState;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.memory.MemoryCandidate;
import cn.lypi.contracts.memory.MemoryWriteRequest;
import cn.lypi.contracts.session.CompactionPlan;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCoreContractCompilationTest {
    @Test
    void contextAssemblerExposesBuildRequestAndViewMetadata() {
        ContextBuildRequest request = new ContextBuildRequest(
            "session-1",
            Optional.of("leaf-entry"),
            Path.of("."),
            true
        );
        ContextAssembly assembly = new ContextAssembly(
            null,
            List.of("entry-1"),
            List.of("summary-1"),
            List.of(),
            false
        );

        ContextAssembler assembler = buildRequest -> assembly;

        assertThat(assembler.build(request).branchEntryIds()).containsExactly("entry-1");
        assertThat(request.includeSystemPrompt()).isTrue();
    }

    @Test
    void compactionCoordinatorExposesPlanningResultWithoutMutatingContext() {
        CompactionDecision decision = new CompactionDecision(
            null,
            Optional.empty(),
            false,
            "within budget"
        );

        CompactionCoordinator coordinator = context -> decision;

        assertThat(coordinator.preflight(null).compacted()).isFalse();
        assertThat(decision.reason()).isEqualTo("within budget");
    }

    @Test
    void memoryExtractionWorkerReportsCandidatesAndWritesSeparately() {
        MemoryExtractionResult result = new MemoryExtractionResult(
            List.of(),
            List.of(),
            List.of("secret-like content ignored"),
            Optional.empty()
        );

        MemoryExtractionWorker worker = state -> result;

        assertThat(worker.extractAfterTurn(null).skippedReasons())
            .containsExactly("secret-like content ignored");
    }

    @SuppressWarnings("unused")
    private void contractTypesStayImportable(
        TurnState state,
        ContextSnapshot snapshot,
        AgentMessage message,
        MemoryCandidate candidate,
        MemoryWriteRequest writeRequest,
        CompactionPlan plan
    ) {
    }
}
