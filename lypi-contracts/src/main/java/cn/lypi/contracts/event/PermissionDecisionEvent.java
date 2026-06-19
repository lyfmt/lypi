package cn.lypi.contracts.event;

import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionOptionKind;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.security.ReviewDecision;
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
    ReviewDecision reviewDecision,
    Optional<PermissionUpdate> appliedUpdate,
    Map<String, Object> metadata,
    Instant timestamp
) implements AgentEvent {
    public PermissionDecisionEvent {
        requestId = blankToDefault(requestId, toolUseId);
        toolName = toolName == null || toolName.isBlank() ? "unknown" : toolName;
        renderedToolUse = renderedToolUse == null ? "" : renderedToolUse;
        selectedOptionId = selectedOptionId == null || selectedOptionId.isBlank() ? "legacy" : selectedOptionId;
        Optional<PermissionUpdate> normalizedAppliedUpdate = appliedUpdate == null ? Optional.empty() : appliedUpdate;
        reviewDecision = reviewDecision == null
            ? inferReviewDecision(selectedOptionId, decision, normalizedAppliedUpdate, metadata)
            : reviewDecision;
        appliedUpdate = normalizedAppliedUpdate;
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
            null,
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
            null,
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

    private static ReviewDecision inferReviewDecision(
        String selectedOptionId,
        PermissionDecision decision,
        Optional<PermissionUpdate> appliedUpdate,
        Map<String, Object> metadata
    ) {
        if (metadata != null) {
            Object explicit = metadata.get("reviewDecision");
            if (explicit instanceof ReviewDecision reviewDecision) {
                return reviewDecision;
            }
            if (explicit instanceof String name && !name.isBlank()) {
                return ReviewDecision.valueOf(name);
            }
        }
        if ("approved".equals(selectedOptionId) || "allow_once".equals(selectedOptionId)) {
            return ReviewDecision.APPROVED;
        }
        if ("approved_exec_policy_amendment".equals(selectedOptionId)
            || "allow_remember".equals(selectedOptionId)
            || selectedOptionId.startsWith("allow_remember")) {
            return ReviewDecision.APPROVED_EXEC_POLICY_AMENDMENT;
        }
        if ("approved_for_session".equals(selectedOptionId)) {
            return ReviewDecision.APPROVED_FOR_SESSION;
        }
        if ("abort".equals(selectedOptionId) || "cancel".equals(selectedOptionId)) {
            return ReviewDecision.ABORT;
        }
        if ("denied".equals(selectedOptionId) || "deny".equals(selectedOptionId)) {
            return ReviewDecision.DENIED;
        }
        if (hasPermissionUpdate(decision) || hasPermissionUpdate(appliedUpdate)) {
            return ReviewDecision.APPROVED_EXEC_POLICY_AMENDMENT;
        }
        if (decision != null && decision.behavior() != null) {
            return switch (decision.behavior()) {
                case ALLOW -> ReviewDecision.APPROVED;
                case DENY -> ReviewDecision.DENIED;
                case ASK -> ReviewDecision.DENIED;
            };
        }
        return PermissionOptionKind.DENY.reviewDecision();
    }

    private static boolean hasPermissionUpdate(PermissionDecision decision) {
        return decision != null && decision.suggestedUpdate() != null && decision.suggestedUpdate().isPresent();
    }

    private static boolean hasPermissionUpdate(Optional<PermissionUpdate> appliedUpdate) {
        return appliedUpdate != null && appliedUpdate.isPresent();
    }
}
