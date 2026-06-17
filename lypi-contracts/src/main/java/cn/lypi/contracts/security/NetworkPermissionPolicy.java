package cn.lypi.contracts.security;

import java.util.Objects;

public record NetworkPermissionPolicy(
    NetworkPolicyMode mode
) {
    public NetworkPermissionPolicy {
        mode = Objects.requireNonNull(mode, "mode");
    }

    public static NetworkPermissionPolicy restricted() {
        return new NetworkPermissionPolicy(NetworkPolicyMode.RESTRICTED);
    }

    public static NetworkPermissionPolicy enabled() {
        return new NetworkPermissionPolicy(NetworkPolicyMode.ENABLED);
    }
}
