package cn.lypi.contracts.event;

import cn.lypi.contracts.security.PermissionDecision;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record PermissionDecisionEvent(
    String sessionId,
    String toolUseId,
    @JsonProperty(defaultValue = "unknown")
    String toolName,
    @JsonProperty(defaultValue = "")
    String renderedToolUse,
    PermissionDecision decision,
    Instant timestamp
) implements AgentEvent {
    public PermissionDecisionEvent {
        toolName = toolName == null || toolName.isBlank() ? "unknown" : toolName;
        renderedToolUse = renderedToolUse == null ? "" : renderedToolUse;
    }

    /**
     * 兼容旧版权限决策事件构造方式。
     */
    public PermissionDecisionEvent(
        String sessionId,
        String toolUseId,
        PermissionDecision decision,
        Instant timestamp
    ) {
        this(sessionId, toolUseId, "unknown", "", decision, timestamp);
    }
}
