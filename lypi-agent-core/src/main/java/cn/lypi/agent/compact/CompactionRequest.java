package cn.lypi.agent.compact;

import cn.lypi.agent.ContextAssembly;
import cn.lypi.agent.ContextBuildRequest;
import java.nio.file.Path;
import java.util.Optional;

public record CompactionRequest(
    String sessionId,
    Optional<String> leafEntryId,
    Path cwd,
    ContextBuildRequest contextBuildRequest,
    ContextAssembly assembly
) {}
