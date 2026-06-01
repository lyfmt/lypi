package cn.lypi.agent;

import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.runtime.AiProviderRuntimePort;
import cn.lypi.contracts.runtime.ResourceRuntimePort;
import cn.lypi.contracts.runtime.SecurityRuntimePort;
import cn.lypi.contracts.runtime.SessionEnginePort;
import cn.lypi.contracts.runtime.ToolRuntimePort;

public record AgentCoreRuntimePorts(
    SessionEnginePort sessionEngine,
    AiProviderRuntimePort aiProvider,
    ToolRuntimePort toolRuntime,
    SecurityRuntimePort securityRuntime,
    ResourceRuntimePort resourceRuntime,
    EventBus eventBus,
    ContextAssembler contextAssembler,
    CompactionCoordinator compactionCoordinator,
    MemoryExtractionWorker memoryExtractionWorker
) {}
