package cn.lypi.contracts.subagent;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.security.AgentMode;
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
    Optional<String> agentRole,
    Optional<ModelSelection> model,
    Optional<ThinkingLevel> thinkingLevel,
    Optional<AgentMode> agentMode,
    boolean permissionModeSpecified
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
            agentRole,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            false
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
            agentRole,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            false
        );
    }

    public SubagentSpawnRequest(
        String parentSessionId,
        String parentEntryId,
        String prompt,
        Path cwd,
        List<String> allowedTools,
        SubagentToolPolicy toolPolicy,
        PermissionMode permissionMode,
        int timeoutSeconds,
        Optional<String> agentName,
        Optional<String> agentRole,
        Optional<ModelSelection> model,
        Optional<ThinkingLevel> thinkingLevel,
        Optional<AgentMode> agentMode
    ) {
        this(
            parentSessionId,
            parentEntryId,
            prompt,
            cwd,
            allowedTools,
            toolPolicy,
            permissionMode,
            timeoutSeconds,
            agentName,
            agentRole,
            model,
            thinkingLevel,
            agentMode,
            true
        );
    }

    public SubagentSpawnRequest {
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        toolPolicy = toolPolicy == null ? new SubagentToolPolicy(allowedTools, allowedTools) : toolPolicy;
        permissionMode = permissionMode == null ? PermissionMode.DEFAULT_EXECUTE : permissionMode;
        agentName = agentName == null ? Optional.empty() : agentName;
        agentRole = agentRole == null ? Optional.empty() : agentRole;
        model = model == null ? Optional.empty() : model;
        thinkingLevel = thinkingLevel == null ? Optional.empty() : thinkingLevel;
        agentMode = agentMode == null ? Optional.empty() : agentMode;
    }

    @JsonGetter("tools")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public List<String> tools() {
        return toolPolicy.requestedTools();
    }
}
