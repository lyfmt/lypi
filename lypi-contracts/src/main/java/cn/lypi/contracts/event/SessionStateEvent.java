package cn.lypi.contracts.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.security.ActivePermissionProfile;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.ApprovalMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionStateEvent(
    String sessionId,
    String leafId,
    ModelSelection model,
    ThinkingLevel thinkingLevel,
    AgentMode agentMode,
    PermissionRuntimeState permissionRuntimeState,
    Instant timestamp
) implements AgentEvent {
    public SessionStateEvent {
        permissionRuntimeState = permissionRuntimeState == null
            ? PermissionRuntimeState.fromLegacy(PermissionMode.ASK)
            : permissionRuntimeState;
    }

    public SessionStateEvent(
        String sessionId,
        String leafId,
        ModelSelection model,
        ThinkingLevel thinkingLevel,
        AgentMode agentMode,
        PermissionMode permissionMode,
        Instant timestamp
    ) {
        this(sessionId, leafId, model, thinkingLevel, agentMode, PermissionRuntimeState.fromLegacy(permissionMode), timestamp);
    }

    /**
     * 返回当前会话发布的审批模式。
     */
    @JsonGetter("approvalMode")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public ApprovalMode approvalMode() {
        return permissionRuntimeState.approvalPolicy().mode();
    }

    /**
     * 返回当前会话激活的权限 profile。
     */
    @JsonGetter("activePermissionProfile")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public ActivePermissionProfile activePermissionProfile() {
        return permissionRuntimeState.activePermissionProfile();
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
    public static SessionStateEvent create(
        @JsonProperty("sessionId") String sessionId,
        @JsonProperty("leafId") String leafId,
        @JsonProperty("model") ModelSelection model,
        @JsonProperty("thinkingLevel") ThinkingLevel thinkingLevel,
        @JsonProperty("agentMode") AgentMode agentMode,
        @JsonProperty("permissionRuntimeState") PermissionRuntimeState permissionRuntimeState,
        @JsonProperty("permissionMode") PermissionMode permissionMode,
        @JsonProperty("timestamp") Instant timestamp
    ) {
        return new SessionStateEvent(
            sessionId,
            leafId,
            model,
            thinkingLevel,
            agentMode,
            permissionRuntimeState == null
                ? PermissionRuntimeState.fromLegacy(permissionMode == null ? PermissionMode.ASK : permissionMode)
                : permissionRuntimeState,
            timestamp
        );
    }
}
