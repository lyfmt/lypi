package cn.lycode.security;

import cn.lycode.contracts.security.NetworkPermissionPolicy;
import cn.lycode.contracts.security.NetworkPolicyMode;
import cn.lycode.contracts.security.PermissionBehavior;
import cn.lycode.contracts.security.PermissionDecision;
import cn.lycode.contracts.security.PermissionDecisionReason;
import cn.lycode.contracts.security.PermissionUpdate;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 检查 permission profile 的网络边界。
 */
public final class NetworkPolicyChecker {
    /**
     * 根据网络策略判定网络访问。
     */
    public PermissionDecision decide(NetworkPermissionPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        return policy.mode() == NetworkPolicyMode.ENABLED
            ? decision(PermissionBehavior.ALLOW, "网络 profile 允许访问。", policy)
            : decision(PermissionBehavior.DENY, "网络 profile 拒绝访问。", policy);
    }

    private PermissionDecision decision(
        PermissionBehavior behavior,
        String message,
        NetworkPermissionPolicy policy
    ) {
        return new PermissionDecision(
            behavior,
            PermissionDecisionReason.SANDBOX_POLICY,
            message,
            Optional.<PermissionUpdate>empty(),
            Map.of("networkMode", policy.mode().name())
        );
    }
}
