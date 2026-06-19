package cn.lypi.contracts.session;

import com.fasterxml.jackson.annotation.JsonCreator;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;

/**
 * 表示从 session 分支恢复出的模型上下文状态。
 *
 * NOTE: 不包含 system prompt、resource snapshot 或 budget；这些由 agent-core 拼装。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionContext(
    List<AgentMessage> messages,
    List<String> branchEntryIds,
    List<String> appliedCompactionEntryIds,
    ModelSelection model,
    ThinkingLevel thinkingLevel,
    AgentMode mode,
    PermissionRuntimeState permissionRuntimeState
) {
    public SessionContext {
        permissionRuntimeState = normalizedPermissionRuntimeState(permissionRuntimeState, null);
    }

    public SessionContext(
        List<AgentMessage> messages,
        List<String> branchEntryIds,
        List<String> appliedCompactionEntryIds,
        ModelSelection model,
        ThinkingLevel thinkingLevel,
        AgentMode mode,
        PermissionMode permissionMode
    ) {
        this(
            messages,
            branchEntryIds,
            appliedCompactionEntryIds,
            model,
            thinkingLevel,
            mode,
            PermissionRuntimeState.fromLegacy(permissionMode)
        );
    }

    /**
     * 返回兼容旧协议的权限模式。
     *
     * NOTE: 新代码应读取 permissionRuntimeState。
     */
    @JsonGetter("permissionMode")
    public PermissionMode permissionMode() {
        return permissionRuntimeState.legacyPermissionMode();
    }

    @JsonCreator
    public static SessionContext create(
        @JsonProperty("messages") List<AgentMessage> messages,
        @JsonProperty("branchEntryIds") List<String> branchEntryIds,
        @JsonProperty("appliedCompactionEntryIds") List<String> appliedCompactionEntryIds,
        @JsonProperty("model") ModelSelection model,
        @JsonProperty("thinkingLevel") ThinkingLevel thinkingLevel,
        @JsonProperty("mode") AgentMode mode,
        @JsonProperty("permissionRuntimeState") PermissionRuntimeState permissionRuntimeState,
        @JsonProperty("permissionMode") PermissionMode permissionMode
    ) {
        return new SessionContext(
            messages,
            branchEntryIds,
            appliedCompactionEntryIds,
            model,
            thinkingLevel,
            mode,
            normalizedPermissionRuntimeState(permissionRuntimeState, permissionMode)
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
