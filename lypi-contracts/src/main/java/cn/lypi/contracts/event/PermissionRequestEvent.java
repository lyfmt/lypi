package cn.lypi.contracts.event;

import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionUpdate;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

public record PermissionRequestEvent(
    String sessionId,
    String toolUseId,
    @JsonProperty(defaultValue = "unknown")
    String toolName,
    @JsonProperty(defaultValue = "")
    String renderedToolUse,
    String message,
    PermissionDecision decision,
    Instant timestamp
) implements AgentEvent {
    public PermissionRequestEvent {
        toolName = toolName == null || toolName.isBlank() ? "unknown" : toolName;
        renderedToolUse = renderedToolUse == null ? "" : renderedToolUse;
        decision = decision == null ? legacyDecision(message) : decision;
    }

    /**
     * 兼容旧版权限请求事件构造方式。
     */
    public PermissionRequestEvent(String sessionId, String toolUseId, String message, Instant timestamp) {
        this(sessionId, toolUseId, "unknown", "", message, legacyDecision(message), timestamp);
    }

    private static PermissionDecision legacyDecision(String message) {
        return new PermissionDecision(
            PermissionBehavior.ASK,
            PermissionDecisionReason.TOOL_SPECIFIC,
            message,
            Optional.<PermissionUpdate>empty(),
            Map.of()
        );
    }
}
