package cn.lycode.agent.compact;

import cn.lycode.agent.ContextAssembly;
import cn.lycode.agent.ContextBuildRequest;
import cn.lycode.contracts.common.AbortSignal;
import cn.lycode.contracts.tool.ToolRegistrySnapshot;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public record CompactionRequest(
    String sessionId,
    Optional<String> leafEntryId,
    Path cwd,
    ContextBuildRequest contextBuildRequest,
    ContextAssembly assembly,
    ToolRegistrySnapshot tools,
    AbortSignal abortSignal
) {
    public CompactionRequest(
        String sessionId,
        Optional<String> leafEntryId,
        Path cwd,
        ContextBuildRequest contextBuildRequest,
        ContextAssembly assembly,
        AbortSignal abortSignal
    ) {
        this(sessionId, leafEntryId, cwd, contextBuildRequest, assembly, new ToolRegistrySnapshot(List.of()), abortSignal);
    }

    public CompactionRequest {
        leafEntryId = leafEntryId == null ? Optional.empty() : leafEntryId;
        cwd = cwd == null ? Path.of(".") : cwd;
        tools = tools == null ? new ToolRegistrySnapshot(List.of()) : tools;
        abortSignal = abortSignal == null ? () -> false : abortSignal;
    }
}
