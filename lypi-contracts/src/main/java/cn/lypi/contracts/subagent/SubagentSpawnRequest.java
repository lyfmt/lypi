package cn.lypi.contracts.subagent;

import cn.lypi.contracts.security.PermissionMode;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public record SubagentSpawnRequest(
    String parentSessionId,
    String parentEntryId,
    String prompt,
    Path cwd,
    List<String> allowedTools,
    PermissionMode permissionMode,
    int timeoutSeconds,
    Optional<String> agentName,
    Optional<String> agentRole
) {
    public SubagentSpawnRequest {
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        agentName = agentName == null ? Optional.empty() : agentName;
        agentRole = agentRole == null ? Optional.empty() : agentRole;
    }
}
