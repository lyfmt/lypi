package cn.lypi.contracts.subagent;

import cn.lypi.contracts.model.ThinkingLevel;
import java.util.List;
import java.util.Optional;

public record SubagentSpawnRequest(
    String parentSessionId,
    String parentEntryId,
    String taskName,
    String message,
    List<String> tools,
    Optional<String> provider,
    Optional<String> model,
    Optional<ThinkingLevel> thinkingLevel
) {
    public SubagentSpawnRequest {
        tools = tools == null ? List.of() : List.copyOf(tools);
        provider = provider == null ? Optional.empty() : provider;
        model = model == null ? Optional.empty() : model;
        thinkingLevel = thinkingLevel == null ? Optional.empty() : thinkingLevel;
    }
}
