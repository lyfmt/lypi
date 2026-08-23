package cn.lycode.agent;

import cn.lycode.agent.compact.CompactionCoordinator;
import cn.lycode.agent.compact.DefaultToolMicroCompactor;
import cn.lycode.agent.compact.ToolMicroCompactor;
import cn.lycode.contracts.event.EventBus;
import cn.lycode.contracts.runtime.AgentCommunicationPort;
import cn.lycode.contracts.runtime.AiProviderRuntimePort;
import cn.lycode.contracts.runtime.CompactStateBackfillPort;
import cn.lycode.contracts.runtime.ResourceRuntimePort;
import cn.lycode.contracts.runtime.SecurityRuntimePort;
import cn.lycode.contracts.runtime.SessionManagerPort;
import cn.lycode.contracts.runtime.ToolRuntimePort;
import java.nio.file.Path;
import java.util.Objects;

public record AgentCoreRuntimePorts(
    Path cwd,
    SessionManagerPort sessionManager,
    AiProviderRuntimePort aiProvider,
    ToolRuntimePort toolRuntime,
    SecurityRuntimePort securityRuntime,
    ResourceRuntimePort resourceRuntime,
    EventBus eventBus,
    ContextAssembler contextAssembler,
    ToolMicroCompactor toolMicroCompactor,
    CompactionCoordinator compactionCoordinator,
    CompactStateBackfillPort compactStateBackfill,
    AgentCommunicationPort agentCommunication,
    MemoryExtractionWorker memoryExtractionWorker
) {
    public AgentCoreRuntimePorts {
        cwd = Objects.requireNonNull(cwd, "cwd must not be null").toAbsolutePath().normalize();
        if (toolMicroCompactor == null) {
            toolMicroCompactor = new DefaultToolMicroCompactor();
        }
        if (compactStateBackfill == null) {
            compactStateBackfill = CompactStateBackfillPort.none();
        }
        if (agentCommunication == null) {
            agentCommunication = AgentCommunicationPort.none();
        }
    }

    public AgentCoreRuntimePorts(
        Path cwd,
        SessionManagerPort sessionManager,
        AiProviderRuntimePort aiProvider,
        ToolRuntimePort toolRuntime,
        SecurityRuntimePort securityRuntime,
        ResourceRuntimePort resourceRuntime,
        EventBus eventBus,
        ContextAssembler contextAssembler,
        ToolMicroCompactor toolMicroCompactor,
        CompactionCoordinator compactionCoordinator,
        CompactStateBackfillPort compactStateBackfill,
        MemoryExtractionWorker memoryExtractionWorker
    ) {
        this(
            cwd,
            sessionManager,
            aiProvider,
            toolRuntime,
            securityRuntime,
            resourceRuntime,
            eventBus,
            contextAssembler,
            toolMicroCompactor,
            compactionCoordinator,
            compactStateBackfill,
            AgentCommunicationPort.none(),
            memoryExtractionWorker
        );
    }

    public AgentCoreRuntimePorts(
        Path cwd,
        SessionManagerPort sessionManager,
        AiProviderRuntimePort aiProvider,
        ToolRuntimePort toolRuntime,
        SecurityRuntimePort securityRuntime,
        ResourceRuntimePort resourceRuntime,
        EventBus eventBus,
        ContextAssembler contextAssembler,
        ToolMicroCompactor toolMicroCompactor,
        CompactionCoordinator compactionCoordinator,
        MemoryExtractionWorker memoryExtractionWorker
    ) {
        this(
            cwd,
            sessionManager,
            aiProvider,
            toolRuntime,
            securityRuntime,
            resourceRuntime,
            eventBus,
            contextAssembler,
            toolMicroCompactor,
            compactionCoordinator,
            CompactStateBackfillPort.none(),
            AgentCommunicationPort.none(),
            memoryExtractionWorker
        );
    }
}
