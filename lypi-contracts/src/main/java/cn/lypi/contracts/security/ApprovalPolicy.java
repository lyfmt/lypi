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

    public static ApprovalPolicy forMode(PermissionMode mode) {
        return switch (Objects.requireNonNull(mode, "mode")) {
            case ASK, AUTO -> new ApprovalPolicy(ApprovalMode.ON_REQUEST);
            case BYPASS -> new ApprovalPolicy(ApprovalMode.NEVER);
        };
    }

    /**
     * 兼容旧数据入口。旧枚举字符串已由 PermissionMode 归一化为三种公开模式。
     */
    public static ApprovalPolicy fromLegacy(PermissionMode legacyPermissionMode) {
        return forMode(legacyPermissionMode);
    }
}
