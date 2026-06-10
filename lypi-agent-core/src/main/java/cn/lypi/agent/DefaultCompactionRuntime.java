package cn.lypi.agent;

import cn.lypi.agent.compact.CompactionCoordinator;
import cn.lypi.agent.compact.CompactionDecision;
import cn.lypi.agent.compact.ManualCompactionPlanner;
import cn.lypi.contracts.runtime.CompactionRequest;
import cn.lypi.contracts.runtime.CompactionResult;
import cn.lypi.contracts.runtime.CompactionRuntimePort;
import java.util.Objects;
import java.util.Optional;

public final class DefaultCompactionRuntime implements CompactionRuntimePort {
    private final ContextAssembler contextAssembler;
    private final CompactionCoordinator compactionCoordinator;

    public DefaultCompactionRuntime(ContextAssembler contextAssembler, CompactionCoordinator compactionCoordinator) {
        this.contextAssembler = Objects.requireNonNull(contextAssembler, "contextAssembler must not be null");
        this.compactionCoordinator = Objects.requireNonNull(compactionCoordinator, "compactionCoordinator must not be null");
    }

    public static ManualCompactionPlanner manualPlanner() {
        return new ManualCompactionPlanner();
    }

    @Override
    public CompactionResult compact(CompactionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        ContextBuildRequest buildRequest = new ContextBuildRequest(
            request.sessionId(),
            request.leafEntryId(),
            request.cwd(),
            true
        );
        ContextAssembly assembly = contextAssembler.build(buildRequest);
        CompactionDecision decision = compactionCoordinator.preflight(new cn.lypi.agent.compact.CompactionRequest(
            request.sessionId(),
            request.leafEntryId(),
            request.cwd(),
            buildRequest,
            assembly,
            request.abortSignal()
        ));
        return new CompactionResult(decision.compacted(), Optional.empty(), decision.reason());
    }
}
