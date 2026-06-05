package cn.lypi.agent;

import java.nio.file.Path;
import java.util.Optional;

public record ContextBuildRequest(
    String sessionId,
    Optional<String> leafEntryId,
    Path cwd,
    boolean includeSystemPrompt
) {}
