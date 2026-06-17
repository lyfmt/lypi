package cn.lypi.contracts.subagent;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SubagentSpawnRequest(
    String parentSessionId,
    String parentEntryId,
    String prompt,
    Path cwd,
    List<String> allowedTools,
    SubagentToolPolicy toolPolicy,
    PermissionRuntimeState permissionRuntimeState,
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
            PermissionRuntimeState.fromLegacy(permissionMode),
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
            PermissionRuntimeState.fromLegacy(permissionMode),
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
            PermissionRuntimeState.fromLegacy(permissionMode),
            timeoutSeconds,
            agentName,
            agentRole,
            model,
            thinkingLevel,
            agentMode,
            true
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
        Optional<AgentMode> agentMode,
        boolean permissionModeSpecified
    ) {
        this(
            parentSessionId,
            parentEntryId,
            prompt,
            cwd,
            allowedTools,
            toolPolicy,
            PermissionRuntimeState.fromLegacy(permissionMode),
            timeoutSeconds,
            agentName,
            agentRole,
            model,
            thinkingLevel,
            agentMode,
            permissionModeSpecified
        );
    }

    public SubagentSpawnRequest {
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        toolPolicy = toolPolicy == null ? new SubagentToolPolicy(allowedTools, allowedTools) : toolPolicy;
        permissionRuntimeState = normalizedPermissionRuntimeState(permissionRuntimeState, null);
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

    @JsonGetter("permissionMode")
    public PermissionMode permissionMode() {
        return permissionRuntimeState.legacyPermissionMode();
    }

    @JsonCreator
    public static SubagentSpawnRequest create(
        @JsonProperty("parentSessionId") String parentSessionId,
        @JsonProperty("parentEntryId") String parentEntryId,
        @JsonProperty("prompt") String prompt,
        @JsonProperty("cwd") Path cwd,
        @JsonProperty("allowedTools") List<String> allowedTools,
        @JsonProperty("toolPolicy") SubagentToolPolicy toolPolicy,
        @JsonProperty("permissionRuntimeState") PermissionRuntimeState permissionRuntimeState,
        @JsonProperty("permissionMode") PermissionMode permissionMode,
        @JsonProperty("timeoutSeconds") int timeoutSeconds,
        @JsonProperty("agentName") Optional<String> agentName,
        @JsonProperty("agentRole") Optional<String> agentRole,
        @JsonProperty("model") Optional<ModelSelection> model,
        @JsonProperty("thinkingLevel") Optional<ThinkingLevel> thinkingLevel,
        @JsonProperty("agentMode") Optional<AgentMode> agentMode,
        @JsonProperty("permissionModeSpecified") Boolean permissionModeSpecified
    ) {
        boolean effectivePermissionModeSpecified = permissionModeSpecified == null
            ? permissionRuntimeState != null || permissionMode != null
            : permissionModeSpecified;
        return new SubagentSpawnRequest(
            parentSessionId,
            parentEntryId,
            prompt,
            cwd,
            allowedTools,
            toolPolicy,
            normalizedPermissionRuntimeState(permissionRuntimeState, permissionMode),
            timeoutSeconds,
            agentName,
            agentRole,
            model,
            thinkingLevel,
            agentMode,
            effectivePermissionModeSpecified
        );
    }

    private static PermissionRuntimeState normalizedPermissionRuntimeState(
        PermissionRuntimeState permissionRuntimeState,
        PermissionMode permissionMode
    ) {
        if (permissionRuntimeState != null) {
            return permissionRuntimeState;
        }
        return PermissionRuntimeState.fromLegacy(Objects.requireNonNullElse(permissionMode, PermissionMode.DEFAULT_EXECUTE));
    }
}
