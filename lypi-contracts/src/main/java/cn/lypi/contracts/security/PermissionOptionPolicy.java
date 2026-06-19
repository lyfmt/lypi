package cn.lypi.contracts.security;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;

/**
 * 根据权限决策生成结构化确认选项。
 */
public final class PermissionOptionPolicy {
    private PermissionOptionPolicy() {
    }

    /**
     * 返回权限确认选项和默认选择。
     *
     * NOTE: 该策略只输出跨模块协议模型，不依赖任何 TUI 行为。
     */
    public static Options fromDecision(PermissionDecision decision) {
        Optional<PermissionUpdate> update = allowedUpdate(decision == null ? Optional.empty() : decision.suggestedUpdate());
        List<PermissionOption> options = update
            .map(permissionUpdate -> List.of(allowOnce(), remember(permissionUpdate), deny()))
            .orElseGet(() -> List.of(allowOnce(), deny()));
        return new Options(
            options,
            optionIdOrFallback(defaultOptionId(decision), options, "allow_once"),
            optionIdOrFallback("cancel", options, fallbackCancelOptionId(options))
        );
    }

    /**
     * 返回指定批准类型的 canonical review options。
     */
    public static Options forApproval(
        ApprovalKind approvalKind,
        Optional<PermissionUpdate> permissionUpdate,
        boolean includeSessionApproval
    ) {
        ApprovalKind normalizedKind = approvalKind == null ? ApprovalKind.COMMAND : approvalKind;
        Optional<PermissionUpdate> update = allowedUpdate(permissionUpdate == null ? Optional.empty() : permissionUpdate);
        List<PermissionOption> options = new ArrayList<>();
        options.add(canonicalApproved());
        if (normalizedKind == ApprovalKind.REQUEST_PERMISSIONS) {
            if (includeSessionApproval) {
                options.add(canonicalApprovedForSession());
            }
            options.add(canonicalDenied());
        } else if (normalizedKind == ApprovalKind.NETWORK) {
            if (includeSessionApproval) {
                options.add(canonicalApprovedForSession());
            }
            options.add(canonicalNetworkPolicyAmendment());
        } else {
            update.ifPresent(updateValue -> options.add(canonicalApprovedExecPolicyAmendment(updateValue)));
            if (includeSessionApproval) {
                options.add(canonicalApprovedForSession());
            }
        }
        options.add(canonicalAbort());
        return new Options(List.copyOf(options), "approved", "abort");
    }

    /**
     * 返回 additional permissions 请求使用的批准选项。
     */
    public static Options forAdditionalPermissionsApproval() {
        return new Options(
            List.of(canonicalApproved(), canonicalAbort()),
            "approved",
            "abort"
        );
    }

    /**
     * 返回是否允许把记住规则写入目标来源。
     */
    public static boolean isAllowedRememberTarget(PermissionRuleSource source) {
        return source == PermissionRuleSource.USER
            || source == PermissionRuleSource.PROJECT
            || source == PermissionRuleSource.SESSION;
    }

    private static Optional<PermissionUpdate> allowedUpdate(Optional<PermissionUpdate> update) {
        return update.filter(permissionUpdate -> isAllowedRememberTarget(permissionUpdate.targetSource()));
    }

    private static String defaultOptionId(PermissionDecision decision) {
        Object explicitDefault = decision == null ? null : decision.metadata().get("defaultOptionId");
        if (explicitDefault instanceof String value && !value.isBlank()) {
            return value;
        }
        Object riskLevel = decision == null ? null : decision.metadata().get("riskLevel");
        if (riskLevel instanceof String value
            && (value.equalsIgnoreCase("HIGH") || value.equalsIgnoreCase("DESTRUCTIVE") || value.equalsIgnoreCase("UNKNOWN"))) {
            return "deny";
        }
        return "allow_once";
    }

    private static String optionIdOrFallback(String optionId, List<PermissionOption> options, String fallbackOptionId) {
        if (hasOption(optionId, options)) {
            return optionId;
        }
        if (hasOption(fallbackOptionId, options)) {
            return fallbackOptionId;
        }
        return options.getFirst().optionId();
    }

    private static String fallbackCancelOptionId(List<PermissionOption> options) {
        return options.stream()
            .filter(option -> option.kind() == PermissionOptionKind.CANCEL)
            .map(PermissionOption::optionId)
            .findFirst()
            .orElseGet(() -> options.stream()
                .filter(option -> option.kind() == PermissionOptionKind.DENY)
                .map(PermissionOption::optionId)
                .findFirst()
                .orElse(options.getFirst().optionId()));
    }

    private static boolean hasOption(String optionId, List<PermissionOption> options) {
        if (optionId == null || optionId.isBlank()) {
            return false;
        }
        return options.stream().anyMatch(option -> option.optionId().equals(optionId));
    }

    private static PermissionOption allowOnce() {
        return new PermissionOption(
            "allow_once",
            PermissionOptionKind.ALLOW_ONCE,
            "允许一次",
            "仅允许当前工具调用。",
            Optional.empty(),
            metadata(ReviewDecision.APPROVED)
        );
    }

    private static PermissionOption remember(PermissionUpdate update) {
        return new PermissionOption(
            "allow_remember",
            PermissionOptionKind.ALLOW_AND_REMEMBER,
            "允许并记住",
            "允许当前工具调用，并记住后续相同请求。",
            Optional.of(update),
            metadata(ReviewDecision.APPROVED_EXEC_POLICY_AMENDMENT)
        );
    }

    private static PermissionOption deny() {
        return new PermissionOption(
            "deny",
            PermissionOptionKind.DENY,
            "拒绝",
            "拒绝当前工具调用。",
            Optional.empty(),
            metadata(ReviewDecision.DENIED)
        );
    }

    private static PermissionOption canonicalApproved() {
        return new PermissionOption(
            "approved",
            PermissionOptionKind.ALLOW_ONCE,
            "批准",
            "允许当前请求。",
            Optional.empty(),
            metadata(ReviewDecision.APPROVED)
        );
    }

    private static PermissionOption canonicalApprovedExecPolicyAmendment(PermissionUpdate update) {
        return new PermissionOption(
            "approved_exec_policy_amendment",
            PermissionOptionKind.ALLOW_AND_REMEMBER,
            "批准并更新策略",
            "允许当前请求，并写入批准策略更新。",
            Optional.of(update),
            metadata(ReviewDecision.APPROVED_EXEC_POLICY_AMENDMENT)
        );
    }

    private static PermissionOption canonicalApprovedForSession() {
        return new PermissionOption(
            "approved_for_session",
            PermissionOptionKind.ALLOW_ONCE,
            "本会话批准",
            "允许当前请求，并在当前会话内复用该批准。",
            Optional.empty(),
            metadata(ReviewDecision.APPROVED_FOR_SESSION)
        );
    }

    private static PermissionOption canonicalDenied() {
        return new PermissionOption(
            "denied",
            PermissionOptionKind.DENY,
            "拒绝",
            "拒绝当前请求，但继续当前会话。",
            Optional.empty(),
            metadata(ReviewDecision.DENIED)
        );
    }

    private static PermissionOption canonicalNetworkPolicyAmendment() {
        return new PermissionOption(
            "network_policy_amendment",
            PermissionOptionKind.ALLOW_ONCE,
            "批准网络策略更新",
            "允许当前请求，并应用网络策略更新。",
            Optional.empty(),
            metadata(ReviewDecision.NETWORK_POLICY_AMENDMENT)
        );
    }

    private static PermissionOption canonicalAbort() {
        return new PermissionOption(
            "abort",
            PermissionOptionKind.CANCEL,
            "中止",
            "停止当前动作，等待下一次用户输入。",
            Optional.empty(),
            metadata(ReviewDecision.ABORT)
        );
    }

    private static Map<String, Object> metadata(ReviewDecision reviewDecision) {
        return Map.of("reviewDecision", reviewDecision);
    }

    public record Options(
        List<PermissionOption> options,
        String defaultOptionId,
        String cancelOptionId
    ) {
        public Options {
            options = options == null ? List.of() : List.copyOf(options);
            if (options.isEmpty()) {
                throw new IllegalArgumentException("options must not be empty");
            }
            if (!hasOption(defaultOptionId, options)) {
                throw new IllegalArgumentException("defaultOptionId must reference an existing option");
            }
            if (!hasOption(cancelOptionId, options)) {
                throw new IllegalArgumentException("cancelOptionId must reference an existing option");
            }
        }

        /**
         * 返回选项中按展示顺序暴露的 canonical review decisions。
         */
        public List<ReviewDecision> reviewDecisions() {
            LinkedHashSet<ReviewDecision> decisions = new LinkedHashSet<>();
            for (PermissionOption option : options) {
                reviewDecisionOf(option).ifPresent(decisions::add);
            }
            return List.copyOf(decisions);
        }
    }

    private static Optional<ReviewDecision> reviewDecisionOf(PermissionOption option) {
        if (option == null) {
            return Optional.empty();
        }
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
