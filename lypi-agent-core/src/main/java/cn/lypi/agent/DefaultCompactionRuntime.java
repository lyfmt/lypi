package cn.lypi.agent;

import cn.lypi.agent.compact.CompactionCoordinator;
import cn.lypi.agent.compact.CompactionDecision;
import cn.lypi.agent.compact.ManualCompactionPlanner;
import cn.lypi.contracts.runtime.CompactionRequest;
import cn.lypi.contracts.runtime.CompactionResult;
import cn.lypi.contracts.runtime.CompactionRuntimePort;
import cn.lypi.contracts.runtime.ToolRuntimePort;
import java.util.Objects;

public final class DefaultCompactionRuntime implements CompactionRuntimePort {
    private final ContextAssembler contextAssembler;
    private final CompactionCoordinator compactionCoordinator;
    private final ToolRuntimePort toolRuntime;

    public DefaultCompactionRuntime(ContextAssembler contextAssembler, CompactionCoordinator compactionCoordinator) {
        this(contextAssembler, compactionCoordinator, null);
    }

    public DefaultCompactionRuntime(
        ContextAssembler contextAssembler,
        CompactionCoordinator compactionCoordinator,
        ToolRuntimePort toolRuntime
    ) {
        this.contextAssembler = Objects.requireNonNull(contextAssembler, "contextAssembler must not be null");
        this.compactionCoordinator = Objects.requireNonNull(compactionCoordinator, "compactionCoordinator must not be null");
        this.toolRuntime = toolRuntime;
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
            toolRuntime == null ? null : toolRuntime.snapshot(),
            request.abortSignal()
        ));
        return new CompactionResult(decision.compacted(), decision.compactionEntryId(), decision.reason());
    }
}
