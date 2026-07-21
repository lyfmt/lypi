package cn.lypi.contracts.tui;

import com.fasterxml.jackson.annotation.JsonCreator;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionRuntimeState(
    String sessionId,
    Path cwd,
    String currentBranchLeafId,
    ModelSelection model,
    ThinkingLevel thinkingLevel,
    AgentMode agentMode,
    PermissionRuntimeState permissionRuntimeState,
    ContextBudget budget,
    List<AgentMessage> transcript,
    boolean hasInterruptibleTool,
    boolean hasActiveTurn,
    boolean hasPendingPermission,
    boolean hasPendingInput
) {
    public SessionRuntimeState {
        permissionRuntimeState = normalizedPermissionRuntimeState(permissionRuntimeState, null);
        transcript = transcript == null ? List.of() : List.copyOf(transcript);
    }

    public SessionRuntimeState(
        String sessionId,
        Path cwd,
        String currentBranchLeafId,
        ModelSelection model,
        ThinkingLevel thinkingLevel,
        AgentMode agentMode,
        PermissionMode permissionMode,
        ContextBudget budget,
        boolean hasInterruptibleTool,
        boolean hasActiveTurn,
        boolean hasPendingPermission,
        boolean hasPendingInput
    ) {
        this(
            sessionId,
            cwd,
            currentBranchLeafId,
            model,
            thinkingLevel,
            agentMode,
            PermissionRuntimeState.fromLegacy(permissionMode),
            budget,
            List.of(),
            hasInterruptibleTool,
            hasActiveTurn,
            hasPendingPermission,
            hasPendingInput
        );
    }

    /**
     * 返回兼容旧协议的权限模式。
     *
     * NOTE: 新代码应读取 permissionRuntimeState。
     */
    @JsonGetter("permissionMode")
    public PermissionMode permissionMode() {
        return permissionRuntimeState.mode();
    }

    @JsonCreator
    public static SessionRuntimeState create(
        @JsonProperty("sessionId") String sessionId,
        @JsonProperty("cwd") Path cwd,
        @JsonProperty("currentBranchLeafId") String currentBranchLeafId,
        @JsonProperty("model") ModelSelection model,
        @JsonProperty("thinkingLevel") ThinkingLevel thinkingLevel,
        @JsonProperty("agentMode") AgentMode agentMode,
        @JsonProperty("permissionRuntimeState") PermissionRuntimeState permissionRuntimeState,
        @JsonProperty("permissionMode") PermissionMode permissionMode,
        @JsonProperty("budget") ContextBudget budget,
        @JsonProperty("transcript") List<AgentMessage> transcript,
        @JsonProperty("hasInterruptibleTool") boolean hasInterruptibleTool,
        @JsonProperty("hasActiveTurn") boolean hasActiveTurn,
        @JsonProperty("hasPendingPermission") boolean hasPendingPermission,
        @JsonProperty("hasPendingInput") boolean hasPendingInput
    ) {
        return new SessionRuntimeState(
            sessionId,
            cwd,
            currentBranchLeafId,
            model,
            thinkingLevel,
            agentMode,
            normalizedPermissionRuntimeState(permissionRuntimeState, permissionMode),
            budget,
            transcript,
            hasInterruptibleTool,
            hasActiveTurn,
            hasPendingPermission,
            hasPendingInput
        );
    }

    public SessionRuntimeState(
        String sessionId,
        Path cwd,
        String currentBranchLeafId,
        ModelSelection model,
        ThinkingLevel thinkingLevel,
        AgentMode agentMode,
        PermissionMode permissionMode,
        ContextBudget budget,
        List<AgentMessage> transcript,
        boolean hasInterruptibleTool,
        boolean hasActiveTurn,
        boolean hasPendingPermission,
        boolean hasPendingInput
    ) {
        this(
            sessionId,
            cwd,
            currentBranchLeafId,
            model,
            thinkingLevel,
            agentMode,
            PermissionRuntimeState.fromLegacy(permissionMode),
            budget,
            transcript,
            hasInterruptibleTool,
            hasActiveTurn,
            hasPendingPermission,
            hasPendingInput
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
