package cn.lypi.contracts.security;

import java.util.Map;
import java.util.Optional;

public record PermissionOption(
    String optionId,
    PermissionOptionKind kind,
    String label,
    String description,
    Optional<PermissionUpdate> permissionUpdate,
    Map<String, Object> metadata
) {
    public PermissionOption {
        if (optionId == null || optionId.isBlank()) {
            throw new IllegalArgumentException("optionId must not be blank");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        label = label == null ? "" : label;
        description = description == null ? "" : description;
        permissionUpdate = permissionUpdate == null ? Optional.empty() : permissionUpdate;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        if (kind == PermissionOptionKind.ALLOW_AND_REMEMBER) {
            PermissionUpdate update = permissionUpdate.orElseThrow(() ->
                new IllegalArgumentException("ALLOW_AND_REMEMBER requires permissionUpdate"));
            validateRememberTarget(update.targetSource());
        }
    }

    private static void validateRememberTarget(PermissionRuleSource targetSource) {
        if (targetSource != PermissionRuleSource.USER
            && targetSource != PermissionRuleSource.PROJECT
            && targetSource != PermissionRuleSource.SESSION) {
            throw new IllegalArgumentException("remember targetSource must be USER, PROJECT, or SESSION");
        }
    }
}
