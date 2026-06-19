package cn.lypi.contracts.session;

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
import java.time.Instant;
import java.util.Optional;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionHeader(
    String type,
    int version,
    String id,
    Path cwd,
    Optional<String> parentSessionId,
    Optional<String> parentSpawnEntryId,
    int depth,
    Optional<String> agentName,
    Optional<String> agentRole,
    Instant timestamp,
    Optional<ModelSelection> initialModel,
    Optional<ThinkingLevel> initialThinkingLevel,
    Optional<AgentMode> initialAgentMode,
    PermissionRuntimeState initialPermissionRuntimeState
) {
    public SessionHeader {
        parentSessionId = parentSessionId == null ? Optional.empty() : parentSessionId;
        parentSpawnEntryId = parentSpawnEntryId == null ? Optional.empty() : parentSpawnEntryId;
        agentName = agentName == null ? Optional.empty() : agentName;
        agentRole = agentRole == null ? Optional.empty() : agentRole;
        initialModel = initialModel == null ? Optional.empty() : initialModel;
        initialThinkingLevel = initialThinkingLevel == null ? Optional.empty() : initialThinkingLevel;
        initialAgentMode = initialAgentMode == null ? Optional.empty() : initialAgentMode;
    }

    public SessionHeader(
        String type,
        int version,
        String id,
        Path cwd,
        Optional<String> parentSessionId,
        Instant timestamp
    ) {
        this(
            type,
            version,
            id,
            cwd,
            parentSessionId,
            Optional.empty(),
            0,
            Optional.empty(),
            Optional.empty(),
            timestamp,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );
    }

    public SessionHeader(
        String type,
        int version,
        String id,
        Path cwd,
        Optional<String> parentSessionId,
        Optional<String> parentSpawnEntryId,
        int depth,
        Optional<String> agentName,
        Optional<String> agentRole,
        Instant timestamp,
        Optional<ModelSelection> initialModel,
        Optional<ThinkingLevel> initialThinkingLevel,
        Optional<AgentMode> initialAgentMode,
        Optional<PermissionMode> initialPermissionMode
    ) {
        this(
            type,
            version,
            id,
            cwd,
            parentSessionId,
            parentSpawnEntryId,
            depth,
            agentName,
            agentRole,
            timestamp,
            initialModel,
            initialThinkingLevel,
            initialAgentMode,
            initialPermissionMode == null
                ? null
                : initialPermissionMode.map(PermissionRuntimeState::fromLegacy).orElse(null)
        );
    }

    public SessionHeader(
        String type,
        int version,
        String id,
        Path cwd,
        Optional<String> parentSessionId,
        Optional<String> parentSpawnEntryId,
        int depth,
        Optional<String> agentName,
        Optional<String> agentRole,
        Instant timestamp
    ) {
        this(
            type,
            version,
            id,
            cwd,
            parentSessionId,
            parentSpawnEntryId,
            depth,
            agentName,
            agentRole,
            timestamp,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
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

    @JsonCreator
    public static SessionHeader create(
        @JsonProperty("type") String type,
        @JsonProperty("version") int version,
        @JsonProperty("id") String id,
        @JsonProperty("cwd") Path cwd,
        @JsonProperty("parentSessionId") Optional<String> parentSessionId,
        @JsonProperty("parentSpawnEntryId") Optional<String> parentSpawnEntryId,
        @JsonProperty("depth") int depth,
        @JsonProperty("agentName") Optional<String> agentName,
        @JsonProperty("agentRole") Optional<String> agentRole,
        @JsonProperty("timestamp") Instant timestamp,
        @JsonProperty("initialModel") Optional<ModelSelection> initialModel,
        @JsonProperty("initialThinkingLevel") Optional<ThinkingLevel> initialThinkingLevel,
        @JsonProperty("initialAgentMode") Optional<AgentMode> initialAgentMode,
        @JsonProperty("initialPermissionRuntimeState") PermissionRuntimeState initialPermissionRuntimeState,
        @JsonProperty("initialPermissionMode") Optional<PermissionMode> initialPermissionMode
    ) {
        PermissionRuntimeState normalizedRuntimeState = initialPermissionRuntimeState;
        if (normalizedRuntimeState == null && initialPermissionMode != null) {
            normalizedRuntimeState = initialPermissionMode.map(PermissionRuntimeState::fromLegacy).orElse(null);
        }
        return new SessionHeader(
            type,
            version,
            id,
            cwd,
            parentSessionId,
            parentSpawnEntryId,
            depth,
            agentName,
            agentRole,
            timestamp,
            initialModel,
            initialThinkingLevel,
            initialAgentMode,
            normalizedRuntimeState
        );
    }
}
