package cn.lypi.contracts.subagent;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import cn.lypi.contracts.security.PermissionMode;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SubagentSpawnRequest(
    String parentSessionId,
    String parentEntryId,
    String prompt,
    Path cwd,
    List<String> allowedTools,
    SubagentToolPolicy toolPolicy,
    PermissionMode permissionMode,
    int timeoutSeconds,
    Optional<String> agentName,
    Optional<String> agentRole
) {
    public SubagentSpawnRequest(
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
        this(
            parentSessionId,
            parentEntryId,
            prompt,
            cwd,
            allowedTools,
            new SubagentToolPolicy(allowedTools, allowedTools),
            permissionMode,
            timeoutSeconds,
            agentName,
            agentRole
        );
    }

    public SubagentSpawnRequest(
        String parentSessionId,
        String parentEntryId,
        String prompt,
        Path cwd,
        List<String> tools,
        List<String> allowedTools,
        PermissionMode permissionMode,
        int timeoutSeconds,
        Optional<String> agentName,
        Optional<String> agentRole
    ) {
        this(
            parentSessionId,
            parentEntryId,
            prompt,
            cwd,
            allowedTools,
            new SubagentToolPolicy(tools, allowedTools),
            permissionMode,
            timeoutSeconds,
            agentName,
            agentRole
        );
    }

    public SubagentSpawnRequest {
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        toolPolicy = toolPolicy == null ? new SubagentToolPolicy(allowedTools, allowedTools) : toolPolicy;
        agentName = agentName == null ? Optional.empty() : agentName;
        agentRole = agentRole == null ? Optional.empty() : agentRole;
    }

    @JsonGetter("tools")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public List<String> tools() {
        return toolPolicy.requestedTools();
    }
}
