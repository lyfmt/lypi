package cn.lypi.contracts.security;

import java.util.Objects;

/**
 * 表示一次网络策略修订。
 */
public record NetworkPolicyAmendment(
    NetworkPermissionPolicy networkPolicy
) {
    public NetworkPolicyAmendment {
        networkPolicy = Objects.requireNonNull(networkPolicy, "networkPolicy");
    }
}
