package cn.lypi.contracts.event;

import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionUpdate;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

public record PermissionDecisionEvent(
    String sessionId,
    String requestId,
    String toolUseId,
    @JsonProperty(defaultValue = "unknown")
    String toolName,
    @JsonProperty(defaultValue = "")
    String renderedToolUse,
    String selectedOptionId,
    PermissionDecision decision,
    Optional<PermissionUpdate> appliedUpdate,
    Map<String, Object> metadata,
    Instant timestamp
) implements AgentEvent {
    public PermissionDecisionEvent {
        requestId = blankToDefault(requestId, toolUseId);
        toolName = toolName == null || toolName.isBlank() ? "unknown" : toolName;
        renderedToolUse = renderedToolUse == null ? "" : renderedToolUse;
        selectedOptionId = selectedOptionId == null || selectedOptionId.isBlank() ? "legacy" : selectedOptionId;
        appliedUpdate = appliedUpdate == null ? Optional.empty() : appliedUpdate;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public PermissionDecisionEvent(
        String sessionId,
        String requestId,
        String toolUseId,
        String toolName,
        String selectedOptionId,
        PermissionDecision decision,
        Optional<PermissionUpdate> appliedUpdate,
        Map<String, Object> metadata,
        Instant timestamp
    ) {
        this(
            sessionId,
            requestId,
            toolUseId,
            toolName,
            "",
            selectedOptionId,
            decision,
            appliedUpdate,
            metadata,
            timestamp
        );
    }

    /**
     * NOTE: 兼容旧版权限决策事件构造方式；legacy 事件没有用户选项事实。
     */
    public PermissionDecisionEvent(
        String sessionId,
        String toolUseId,
        String toolName,
        String renderedToolUse,
        PermissionDecision decision,
        Instant timestamp
    ) {
        this(
            sessionId,
            toolUseId,
            toolUseId,
            toolName,
            renderedToolUse,
            "legacy",
            decision,
            Optional.empty(),
            Map.of("legacy", true),
            timestamp
        );
    }

    /**
     * NOTE: 兼容旧版权限决策事件构造方式；legacy 事件没有用户选项事实。
     */
    public PermissionDecisionEvent(
        String sessionId,
        String toolUseId,
        PermissionDecision decision,
        Instant timestamp
    ) {
        this(sessionId, toolUseId, "unknown", "", decision, timestamp);
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
