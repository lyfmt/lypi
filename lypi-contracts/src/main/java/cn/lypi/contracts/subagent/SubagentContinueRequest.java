package cn.lypi.contracts.subagent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
public record SubagentContinueRequest(
    String parentSessionId,
    String parentEntryId,
    String childSessionId,
    String prompt,
    Path cwd,
    List<String> allowedTools,
    SubagentToolPolicy toolPolicy,
    PermissionRuntimeState permissionRuntimeState,
    int timeoutSeconds,
    Optional<ModelSelection> model,
    Optional<ThinkingLevel> thinkingLevel,
    Optional<AgentMode> agentMode,
    boolean permissionRuntimeStateSpecified
) {
    public SubagentContinueRequest {
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        toolPolicy = toolPolicy == null ? new SubagentToolPolicy(allowedTools, allowedTools) : toolPolicy;
        permissionRuntimeState = normalizedPermissionRuntimeState(permissionRuntimeState, null);
        model = model == null ? Optional.empty() : model;
        thinkingLevel = thinkingLevel == null ? Optional.empty() : thinkingLevel;
        agentMode = agentMode == null ? Optional.empty() : agentMode;
    }

    public SubagentContinueRequest(
        String parentSessionId,
        String parentEntryId,
        String childSessionId,
        String prompt,
        Path cwd,
        List<String> allowedTools,
        int timeoutSeconds
    ) {
        this(
            parentSessionId,
            parentEntryId,
            childSessionId,
            prompt,
            cwd,
            allowedTools,
            new SubagentToolPolicy(allowedTools, allowedTools),
            PermissionRuntimeState.fromLegacy(PermissionMode.DEFAULT_EXECUTE),
            timeoutSeconds,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            false
        );
    }

    public SubagentContinueRequest(
        String childSessionId,
        String prompt,
        List<String> tools,
        int timeoutSeconds
    ) {
        this(
            null,
            null,
            childSessionId,
            prompt,
            null,
            tools,
            new SubagentToolPolicy(tools, tools),
            PermissionRuntimeState.fromLegacy(PermissionMode.DEFAULT_EXECUTE),
            timeoutSeconds,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            false
        );
    }

    public SubagentContinueRequest(
        String parentSessionId,
        String parentEntryId,
        String childSessionId,
        String prompt,
        Path cwd,
        List<String> allowedTools,
        SubagentToolPolicy toolPolicy,
        PermissionMode permissionMode,
        int timeoutSeconds,
        Optional<ModelSelection> model,
        Optional<ThinkingLevel> thinkingLevel,
        Optional<AgentMode> agentMode
    ) {
        this(
            parentSessionId,
            parentEntryId,
            childSessionId,
            prompt,
            cwd,
            allowedTools,
            toolPolicy,
            PermissionRuntimeState.fromLegacy(permissionMode),
            timeoutSeconds,
            model,
            thinkingLevel,
            agentMode,
            true
        );
    }

    public SubagentContinueRequest(
        String parentSessionId,
        String parentEntryId,
        String childSessionId,
        String prompt,
        Path cwd,
        List<String> allowedTools,
        SubagentToolPolicy toolPolicy,
        PermissionRuntimeState permissionRuntimeState,
        int timeoutSeconds,
        Optional<ModelSelection> model,
        Optional<ThinkingLevel> thinkingLevel,
        Optional<AgentMode> agentMode
    ) {
        this(
            parentSessionId,
            parentEntryId,
            childSessionId,
            prompt,
            cwd,
            allowedTools,
            toolPolicy,
            permissionRuntimeState,
            timeoutSeconds,
            model,
            thinkingLevel,
            agentMode,
            true
        );
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
    public static SubagentContinueRequest create(
        @JsonProperty("parentSessionId") String parentSessionId,
        @JsonProperty("parentEntryId") String parentEntryId,
        @JsonProperty("childSessionId") String childSessionId,
        @JsonProperty("prompt") String prompt,
        @JsonProperty("cwd") Path cwd,
        @JsonProperty("allowedTools") List<String> allowedTools,
        @JsonProperty("toolPolicy") SubagentToolPolicy toolPolicy,
        @JsonProperty("permissionRuntimeState") PermissionRuntimeState permissionRuntimeState,
        @JsonProperty("permissionMode") PermissionMode permissionMode,
        @JsonProperty("timeoutSeconds") int timeoutSeconds,
        @JsonProperty("model") Optional<ModelSelection> model,
        @JsonProperty("thinkingLevel") Optional<ThinkingLevel> thinkingLevel,
        @JsonProperty("agentMode") Optional<AgentMode> agentMode,
        @JsonProperty("permissionRuntimeStateSpecified") Boolean permissionRuntimeStateSpecified
    ) {
        boolean effectivePermissionRuntimeStateSpecified = permissionRuntimeStateSpecified == null
            ? permissionRuntimeState != null || permissionMode != null
            : permissionRuntimeStateSpecified;
        return new SubagentContinueRequest(
            parentSessionId,
            parentEntryId,
            childSessionId,
            prompt,
            cwd,
            allowedTools,
            toolPolicy,
            normalizedPermissionRuntimeState(permissionRuntimeState, permissionMode),
            timeoutSeconds,
            model,
            thinkingLevel,
            agentMode,
            effectivePermissionRuntimeStateSpecified
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
