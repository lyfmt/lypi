package cn.lypi.agent;

import cn.lypi.agent.compact.CompactionCoordinator;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.runtime.AiProviderRuntimePort;
import cn.lypi.contracts.runtime.ResourceRuntimePort;
import cn.lypi.contracts.runtime.SecurityRuntimePort;
import cn.lypi.contracts.runtime.SessionEnginePort;
import cn.lypi.contracts.runtime.ToolRuntimePort;
import java.nio.file.Path;
import java.util.Objects;

public record AgentCoreRuntimePorts(
    Path cwd,
    SessionEnginePort sessionEngine,
    AiProviderRuntimePort aiProvider,
    ToolRuntimePort toolRuntime,
    SecurityRuntimePort securityRuntime,
    ResourceRuntimePort resourceRuntime,
    EventBus eventBus,
    ContextAssembler contextAssembler,
    CompactionCoordinator compactionCoordinator,
    MemoryExtractionWorker memoryExtractionWorker
) {
    public AgentCoreRuntimePorts {
        cwd = Objects.requireNonNull(cwd, "cwd must not be null").toAbsolutePath().normalize();
    }
}
