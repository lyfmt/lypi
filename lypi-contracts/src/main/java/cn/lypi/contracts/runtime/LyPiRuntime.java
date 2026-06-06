package cn.lypi.contracts.runtime;

import cn.lypi.contracts.transport.TransportAdapter;
import java.util.List;

public record LyPiRuntime(
    AppEntry appEntry,
    SessionManagerPort sessionManager,
    AgentCorePort agentCore,
    AiProviderRuntimePort aiProvider,
    ToolRuntimePort toolRuntime,
    SecurityRuntimePort securityRuntime,
    ResourceRuntimePort resourceRuntime,
    List<TransportAdapter> transports
) {}
