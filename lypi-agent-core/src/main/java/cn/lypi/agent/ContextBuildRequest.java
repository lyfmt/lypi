package cn.lypi.agent;

import java.util.Optional;

public record ContextBuildRequest(
    String sessionId,
    Optional<String> leafEntryId,
    boolean includeSystemPrompt
) {}
