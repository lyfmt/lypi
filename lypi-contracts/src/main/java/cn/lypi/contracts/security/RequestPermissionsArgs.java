package cn.lypi.contracts.security;

import java.util.Optional;

public record RequestPermissionsArgs(
    Optional<String> environmentId,
    Optional<String> reason,
    RequestPermissionProfile permissions
) {
    public RequestPermissionsArgs {
        environmentId = normalize(environmentId);
        reason = normalize(reason);
        permissions = permissions == null ? RequestPermissionProfile.empty() : permissions;
    }

    private static Optional<String> normalize(Optional<String> value) {
        return value == null ? Optional.empty() : value.filter(text -> !text.isBlank());
    }
}
