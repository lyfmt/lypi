package cn.lypi.contracts.session;

import com.fasterxml.jackson.annotation.JsonCreator;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.subagent.SubagentToolPolicy;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.file.Path;
import java.util.Optional;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ChildSessionRequest(
    String childSessionId,
    String parentSessionId,
    String parentSpawnEntryId,
    Path sessionCwd,
    Path cwd,
    int depth,
    Optional<String> agentName,
    Optional<String> agentRole,
    Optional<String> initialSystemPrompt,
    Optional<ModelSelection> initialModel,
    Optional<ThinkingLevel> initialThinkingLevel,
    Optional<AgentMode> initialAgentMode,
    PermissionRuntimeState initialPermissionRuntimeState,
    SubagentToolPolicy toolPolicy
) {
    public ChildSessionRequest(
        String childSessionId,
        String parentSessionId,
        String parentSpawnEntryId,
        Path sessionCwd,
        Path cwd,
        int depth,
        Optional<String> agentName,
        Optional<String> agentRole
    ) {
        this(
            childSessionId,
            parentSessionId,
            parentSpawnEntryId,
            sessionCwd,
            cwd,
            depth,
            agentName,
            agentRole,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            null,
            SubagentToolPolicy.empty()
        );
    }

    public ChildSessionRequest(
        String childSessionId,
        String parentSessionId,
        String parentSpawnEntryId,
        Path cwd,
        int depth,
        Optional<String> agentName,
        Optional<String> agentRole
    ) {
        this(childSessionId, parentSessionId, parentSpawnEntryId, cwd, cwd, depth, agentName, agentRole);
    }

    public ChildSessionRequest {
        sessionCwd = sessionCwd == null ? cwd : sessionCwd;
        cwd = cwd == null ? sessionCwd : cwd;
        agentName = agentName == null ? Optional.empty() : agentName;
        agentRole = agentRole == null ? Optional.empty() : agentRole;
        initialSystemPrompt = initialSystemPrompt == null ? Optional.empty() : initialSystemPrompt;
        initialModel = initialModel == null ? Optional.empty() : initialModel;
        initialThinkingLevel = initialThinkingLevel == null ? Optional.empty() : initialThinkingLevel;
        initialAgentMode = initialAgentMode == null ? Optional.empty() : initialAgentMode;
        toolPolicy = toolPolicy == null ? SubagentToolPolicy.empty() : toolPolicy;
    }

    public ChildSessionRequest(
        String childSessionId,
        String parentSessionId,
        String parentSpawnEntryId,
        Path sessionCwd,
        Path cwd,
        int depth,
        Optional<String> agentName,
        Optional<String> agentRole,
        Optional<ModelSelection> initialModel,
        Optional<ThinkingLevel> initialThinkingLevel,
        Optional<AgentMode> initialAgentMode,
        PermissionRuntimeState initialPermissionRuntimeState,
        SubagentToolPolicy toolPolicy
    ) {
        this(
            childSessionId,
            parentSessionId,
            parentSpawnEntryId,
            sessionCwd,
            cwd,
            depth,
            agentName,
            agentRole,
            Optional.empty(),
            initialModel,
            initialThinkingLevel,
            initialAgentMode,
            initialPermissionRuntimeState,
            toolPolicy
        );
    }

    public ChildSessionRequest(
        String childSessionId,
        String parentSessionId,
        String parentSpawnEntryId,
        Path sessionCwd,
        Path cwd,
        int depth,
        Optional<String> agentName,
        Optional<String> agentRole,
        Optional<ModelSelection> initialModel,
        Optional<ThinkingLevel> initialThinkingLevel,
        Optional<AgentMode> initialAgentMode,
        Optional<PermissionMode> initialPermissionMode,
        SubagentToolPolicy toolPolicy
    ) {
        this(
            childSessionId,
            parentSessionId,
            parentSpawnEntryId,
            sessionCwd,
            cwd,
            depth,
            agentName,
            agentRole,
            Optional.empty(),
            initialModel,
            initialThinkingLevel,
            initialAgentMode,
            initialPermissionMode == null
                ? null
                : initialPermissionMode.map(PermissionRuntimeState::fromLegacy).orElse(null),
            toolPolicy
        );
    }

    /**
     * 返回兼容旧协议的初始权限模式。
     *
     * NOTE: 新代码应读取 initialPermissionRuntimeState。
     */
    @JsonGetter("initialPermissionMode")
    public Optional<PermissionMode> initialPermissionMode() {
        return Optional.ofNullable(initialPermissionRuntimeState)
            .map(PermissionRuntimeState::legacyPermissionMode);
    }

    /**
     * 返回 canonical 初始权限运行态。
     */
    public Optional<PermissionRuntimeState> permissionRuntimeState() {
        return Optional.ofNullable(initialPermissionRuntimeState);
    }

    @JsonCreator
    public static ChildSessionRequest create(
        @JsonProperty("childSessionId") String childSessionId,
        @JsonProperty("parentSessionId") String parentSessionId,
        @JsonProperty("parentSpawnEntryId") String parentSpawnEntryId,
        @JsonProperty("sessionCwd") Path sessionCwd,
        @JsonProperty("cwd") Path cwd,
        @JsonProperty("depth") int depth,
        @JsonProperty("agentName") Optional<String> agentName,
        @JsonProperty("agentRole") Optional<String> agentRole,
        @JsonProperty("initialSystemPrompt") Optional<String> initialSystemPrompt,
        @JsonProperty("initialModel") Optional<ModelSelection> initialModel,
        @JsonProperty("initialThinkingLevel") Optional<ThinkingLevel> initialThinkingLevel,
        @JsonProperty("initialAgentMode") Optional<AgentMode> initialAgentMode,
        @JsonProperty("initialPermissionRuntimeState") PermissionRuntimeState initialPermissionRuntimeState,
        @JsonProperty("initialPermissionMode") Optional<PermissionMode> initialPermissionMode,
        @JsonProperty("toolPolicy") SubagentToolPolicy toolPolicy
    ) {
        return new ChildSessionRequest(
            childSessionId,
            parentSessionId,
            parentSpawnEntryId,
            sessionCwd,
            cwd,
            depth,
            agentName,
            agentRole,
            initialSystemPrompt,
            initialModel,
            initialThinkingLevel,
            initialAgentMode,
            initialPermissionRuntimeState != null
                ? initialPermissionRuntimeState
                : initialPermissionMode == null ? null : initialPermissionMode.map(PermissionRuntimeState::fromLegacy).orElse(null),
            toolPolicy
        );
    }
}
