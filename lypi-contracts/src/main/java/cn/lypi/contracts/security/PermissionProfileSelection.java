package cn.lypi.contracts.security;

import java.util.Objects;

/**
 * 表示编译后的 active profile 身份和可执行 profile。
 */
public record PermissionProfileSelection(
    ActivePermissionProfile activePermissionProfile,
    PermissionProfile permissionProfile
) {
    public PermissionProfileSelection {
        activePermissionProfile = Objects.requireNonNull(activePermissionProfile, "activePermissionProfile");
        permissionProfile = Objects.requireNonNull(permissionProfile, "permissionProfile");
    }
}
