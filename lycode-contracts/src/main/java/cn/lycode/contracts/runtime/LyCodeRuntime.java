package cn.lycode.contracts.runtime;

import cn.lycode.contracts.transport.TransportAdapter;
import java.util.List;

public record LyCodeRuntime(
    AppEntry appEntry,
    SessionManagerPort sessionManager,
    AgentCorePort agentCore,
    AiProviderRuntimePort aiProvider,
    ToolRuntimePort toolRuntime,
    SecurityRuntimePort securityRuntime,
    ResourceRuntimePort resourceRuntime,
    CompactionRuntimePort compactionRuntime,
    List<TransportAdapter> transports
) {}
