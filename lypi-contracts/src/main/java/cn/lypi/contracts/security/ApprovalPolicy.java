package cn.lypi.contracts.security;

import java.util.Objects;
import java.util.Optional;

public record ApprovalPolicy(
    ApprovalMode mode,
    Optional<GranularApprovalPolicy> granularApprovalPolicy
) {
    public ApprovalPolicy {
        mode = Objects.requireNonNull(mode, "mode");
        granularApprovalPolicy = granularApprovalPolicy == null ? Optional.empty() : granularApprovalPolicy;
        if (mode == ApprovalMode.GRANULAR && granularApprovalPolicy.isEmpty()) {
            throw new IllegalArgumentException("GRANULAR approval mode requires granularApprovalPolicy");
        }
        if (mode != ApprovalMode.GRANULAR && granularApprovalPolicy.isPresent()) {
            throw new IllegalArgumentException("granularApprovalPolicy requires GRANULAR approval mode");
        }
    }

    public ApprovalPolicy(ApprovalMode mode) {
        this(mode, Optional.empty());
    }

    /**
     * 从旧权限模式派生审批策略。
     *
     * NOTE: 该映射只用于兼容旧入口；新代码应读取 PermissionRuntimeState。
     */
    public static ApprovalPolicy fromLegacy(PermissionMode legacyPermissionMode) {
        return switch (Objects.requireNonNull(legacyPermissionMode, "legacyPermissionMode")) {
            case DEFAULT_EXECUTE, ACCEPT_EDITS -> new ApprovalPolicy(ApprovalMode.ON_REQUEST);
            case BYPASS -> new ApprovalPolicy(ApprovalMode.NEVER);
        };
    }
}
