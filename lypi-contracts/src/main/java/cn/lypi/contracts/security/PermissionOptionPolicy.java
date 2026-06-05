package cn.lypi.contracts.security;

import java.util.List;
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
            .map(permissionUpdate -> List.of(allowOnce(), remember(permissionUpdate), deny(), cancel()))
            .orElseGet(() -> List.of(allowOnce(), deny(), cancel()));
        return new Options(options, defaultOptionId(decision), "cancel");
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

    private static PermissionOption allowOnce() {
        return new PermissionOption(
            "allow_once",
            PermissionOptionKind.ALLOW_ONCE,
            "允许一次",
            "仅允许当前工具调用。",
            Optional.empty(),
            Map.of()
        );
    }

    private static PermissionOption remember(PermissionUpdate update) {
        return new PermissionOption(
            "allow_remember",
            PermissionOptionKind.ALLOW_AND_REMEMBER,
            "允许并记住",
            "允许当前工具调用，并记住后续相同请求。",
            Optional.of(update),
            Map.of()
        );
    }

    private static PermissionOption deny() {
        return new PermissionOption(
            "deny",
            PermissionOptionKind.DENY,
            "拒绝",
            "拒绝当前工具调用。",
            Optional.empty(),
            Map.of()
        );
    }

    private static PermissionOption cancel() {
        return new PermissionOption(
            "cancel",
            PermissionOptionKind.CANCEL,
            "取消",
            "取消权限请求。",
            Optional.empty(),
            Map.of()
        );
    }

    public record Options(
        List<PermissionOption> options,
        String defaultOptionId,
        String cancelOptionId
    ) {
        public Options {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }
}
