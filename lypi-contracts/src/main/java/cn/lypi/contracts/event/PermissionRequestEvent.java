package cn.lypi.contracts.event;

import cn.lypi.contracts.security.AdditionalPermissionProfile;
import cn.lypi.contracts.security.ApprovalKind;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionOption;
import cn.lypi.contracts.security.PermissionOptionKind;
import cn.lypi.contracts.security.ReviewDecision;
import cn.lypi.contracts.security.PermissionUpdate;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

public record PermissionRequestEvent(
    String sessionId,
    String requestId,
    String toolUseId,
    @JsonProperty(defaultValue = "unknown")
    String toolName,
    String displayTitle,
    @JsonProperty(defaultValue = "")
    String renderedToolUse,
    String message,
    PermissionDecision policyDecision,
    ApprovalKind approvalKind,
    List<ReviewDecision> availableDecisions,
    Optional<AdditionalPermissionProfile> additionalPermissions,
    boolean strictAutoReview,
    List<PermissionOption> options,
    String defaultOptionId,
    String cancelOptionId,
    Map<String, Object> metadata,
    Instant timestamp
) implements AgentEvent {
    public PermissionRequestEvent {
        requestId = blankToDefault(requestId, toolUseId);
        toolName = toolName == null || toolName.isBlank() ? "unknown" : toolName;
        displayTitle = blankToDefault(displayTitle, message);
        renderedToolUse = renderedToolUse == null ? "" : renderedToolUse;
        policyDecision = policyDecision == null ? legacyDecision(message) : policyDecision;
        message = blankToDefault(message, policyDecision.message());
        approvalKind = approvalKind == null ? ApprovalKind.COMMAND : approvalKind;
        additionalPermissions = additionalPermissions == null ? Optional.empty() : additionalPermissions;
        options = normalizeOptions(options);
        availableDecisions = normalizeAvailableDecisions(availableDecisions, options);
        defaultOptionId = blankToDefault(defaultOptionId, defaultOption(options).optionId());
        cancelOptionId = blankToDefault(cancelOptionId, cancelOption(options).optionId());
        validateOptionId("defaultOptionId", defaultOptionId, options);
        validateOptionId("cancelOptionId", cancelOptionId, options);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * NOTE: 兼容旧版权限请求事件构造方式；legacy 事件默认提供允许一次、拒绝和取消三个选项。
     */
    public PermissionRequestEvent(String sessionId, String toolUseId, String message, Instant timestamp) {
        this(sessionId, toolUseId, "unknown", "", message, legacyDecision(message), timestamp);
    }

    public PermissionRequestEvent(
        String sessionId,
        String requestId,
        String toolUseId,
        String toolName,
        String displayTitle,
        String renderedToolUse,
        PermissionDecision policyDecision,
        List<PermissionOption> options,
        String defaultOptionId,
        String cancelOptionId,
        Map<String, Object> metadata,
        Instant timestamp
    ) {
        this(
            sessionId,
            requestId,
            toolUseId,
            toolName,
            displayTitle,
            renderedToolUse,
            policyDecision == null ? displayTitle : policyDecision.message(),
            policyDecision,
            ApprovalKind.COMMAND,
            List.of(),
            Optional.empty(),
            false,
            options,
            defaultOptionId,
            cancelOptionId,
            metadata,
            timestamp
        );
    }

    public PermissionRequestEvent(
        String sessionId,
        String requestId,
        String toolUseId,
        String toolName,
        String displayTitle,
        String renderedToolUse,
        String message,
        PermissionDecision policyDecision,
        List<PermissionOption> options,
        String defaultOptionId,
        String cancelOptionId,
        Map<String, Object> metadata,
        Instant timestamp
    ) {
        this(
            sessionId,
            requestId,
            toolUseId,
            toolName,
            displayTitle,
            renderedToolUse,
            message,
            policyDecision,
            ApprovalKind.COMMAND,
            List.of(),
            Optional.empty(),
            false,
            options,
            defaultOptionId,
            cancelOptionId,
            metadata,
            timestamp
        );
    }

    /**
     * NOTE: 兼容旧版权限请求事件构造方式；legacy 事件默认提供允许一次、拒绝和取消三个选项。
     */
    public PermissionRequestEvent(
        String sessionId,
        String toolUseId,
        String toolName,
        String renderedToolUse,
        String message,
        PermissionDecision decision,
        Instant timestamp
    ) {
        this(
            sessionId,
            toolUseId,
            toolUseId,
            toolName,
            message,
            renderedToolUse,
            message,
            decision,
            ApprovalKind.COMMAND,
            List.of(),
            Optional.empty(),
            false,
            legacyOptions(),
            "allow_once",
            "cancel",
            Map.of("legacy", true),
            timestamp
        );
    }

    /**
     * 返回旧字段名称下的权限决策。
     */
    public PermissionDecision decision() {
        return policyDecision;
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

    private static List<PermissionOption> normalizeOptions(List<PermissionOption> options) {
        if (options == null || options.isEmpty()) {
            return legacyOptions();
        }
        return List.copyOf(options);
    }

    private static List<ReviewDecision> normalizeAvailableDecisions(
        List<ReviewDecision> availableDecisions,
        List<PermissionOption> options
    ) {
        LinkedHashSet<ReviewDecision> optionDecisions = new LinkedHashSet<>();
        for (PermissionOption option : options) {
            reviewDecisionOf(option).ifPresent(optionDecisions::add);
        }
        if (availableDecisions != null && !availableDecisions.isEmpty()) {
            LinkedHashSet<ReviewDecision> explicit = new LinkedHashSet<>(availableDecisions);
            if (!optionDecisions.containsAll(explicit)) {
                throw new IllegalArgumentException("availableDecisions must be represented by options");
            }
            return List.copyOf(explicit);
        }
        return List.copyOf(optionDecisions);
    }

    private static List<PermissionOption> legacyOptions() {
        return List.of(
            new PermissionOption(
                "allow_once",
                PermissionOptionKind.ALLOW_ONCE,
                "允许一次",
                "仅允许当前工具调用。",
                Optional.empty(),
                Map.of("legacy", true)
            ),
            new PermissionOption(
                "deny",
                PermissionOptionKind.DENY,
                "拒绝",
                "拒绝当前工具调用。",
                Optional.empty(),
                Map.of("legacy", true)
            ),
            new PermissionOption(
                "cancel",
                PermissionOptionKind.CANCEL,
                "取消",
                "取消权限请求。",
                Optional.empty(),
                Map.of("legacy", true)
            )
        );
    }

    private static PermissionOption defaultOption(List<PermissionOption> options) {
        return options.getFirst();
    }

    private static PermissionOption cancelOption(List<PermissionOption> options) {
        return options.stream()
            .filter(option -> option.kind() == PermissionOptionKind.CANCEL)
            .findFirst()
            .orElseGet(() -> options.stream()
                .filter(option -> option.kind() == PermissionOptionKind.DENY)
                .findFirst()
                .orElse(options.getFirst()));
    }

    private static void validateOptionId(String fieldName, String optionId, List<PermissionOption> options) {
        Set<String> optionIds = options.stream().map(PermissionOption::optionId).collect(Collectors.toSet());
        if (!optionIds.contains(optionId)) {
            throw new IllegalArgumentException(fieldName + " must reference an existing option");
        }
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static Optional<ReviewDecision> reviewDecisionOf(PermissionOption option) {
        Object value = option.metadata().get("reviewDecision");
        if (value instanceof ReviewDecision reviewDecision) {
            return Optional.of(reviewDecision);
        }
        if (value instanceof String name && !name.isBlank()) {
            return Optional.of(ReviewDecision.valueOf(name));
        }
        return Optional.of(option.kind().reviewDecision());
    }
}
