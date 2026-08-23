package cn.lycode.contracts.security;

public record RequestPermissionsResponse(
    RequestPermissionProfile permissions,
    PermissionGrantScope scope,
    boolean strictAutoReview
) {
    public RequestPermissionsResponse {
        permissions = permissions == null ? RequestPermissionProfile.empty() : permissions;
        scope = scope == null ? PermissionGrantScope.TURN : scope;
    }
}
