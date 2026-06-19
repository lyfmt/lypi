package cn.lypi.contracts.security;

public record RequestPermissionProfile(
    AdditionalPermissionProfile additionalPermissions
) {
    public RequestPermissionProfile {
        additionalPermissions = additionalPermissions == null ? AdditionalPermissionProfile.empty() : additionalPermissions;
    }

    public static RequestPermissionProfile empty() {
        return new RequestPermissionProfile(AdditionalPermissionProfile.empty());
    }
}
