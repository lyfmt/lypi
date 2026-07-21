package cn.lypi.contracts.context;

import com.fasterxml.jackson.annotation.JsonCreator;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ContextSnapshot(
    SystemPrompt systemPrompt,
    List<AgentMessage> messages,
    ModelSelection model,
    ThinkingLevel thinkingLevel,
    AgentMode mode,
    PermissionRuntimeState permissionRuntimeState,
    ContextBudget budget
) {
    public ContextSnapshot {
        permissionRuntimeState = normalizedPermissionRuntimeState(permissionRuntimeState, null);
    }

    public ContextSnapshot(
        SystemPrompt systemPrompt,
        List<AgentMessage> messages,
        ModelSelection model,
        ThinkingLevel thinkingLevel,
        AgentMode mode,
        PermissionMode permissionMode,
        ContextBudget budget
    ) {
        this(
            systemPrompt,
            messages,
            model,
            thinkingLevel,
            mode,
            PermissionRuntimeState.fromLegacy(permissionMode),
            budget
        );
    }

    @JsonGetter("permissionMode")
    public PermissionMode permissionMode() {
        return permissionRuntimeState.mode();
    }

    @JsonCreator
    public static ContextSnapshot create(
        @JsonProperty("systemPrompt") SystemPrompt systemPrompt,
        @JsonProperty("messages") List<AgentMessage> messages,
        @JsonProperty("model") ModelSelection model,
        @JsonProperty("thinkingLevel") ThinkingLevel thinkingLevel,
        @JsonProperty("mode") AgentMode mode,
        @JsonProperty("permissionRuntimeState") PermissionRuntimeState permissionRuntimeState,
        @JsonProperty("permissionMode") PermissionMode permissionMode,
        @JsonProperty("budget") ContextBudget budget
    ) {
        return new ContextSnapshot(
            systemPrompt,
            messages,
            model,
            thinkingLevel,
            mode,
            normalizedPermissionRuntimeState(permissionRuntimeState, permissionMode),
            budget
        );
    }

    private static PermissionRuntimeState normalizedPermissionRuntimeState(
        PermissionRuntimeState permissionRuntimeState,
        PermissionMode permissionMode
    ) {
        if (permissionRuntimeState != null) {
            return permissionRuntimeState;
        }
        return PermissionRuntimeState.fromLegacy(Objects.requireNonNullElse(permissionMode, PermissionMode.ASK));
    }
}
