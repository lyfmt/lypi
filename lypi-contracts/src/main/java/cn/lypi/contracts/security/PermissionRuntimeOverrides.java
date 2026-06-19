package cn.lypi.contracts.security;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public record PermissionRuntimeOverrides(
    Optional<ApprovalPolicy> approvalPolicy,
    Optional<ActivePermissionProfile> activePermissionProfile,
    Optional<Path> cwd,
    Optional<List<Path>> workspaceRoots,
    Optional<Boolean> strictAutoReview,
    Optional<PermissionMode> legacyPermissionMode
) {
    public PermissionRuntimeOverrides {
        approvalPolicy = approvalPolicy == null ? Optional.empty() : approvalPolicy;
        activePermissionProfile = activePermissionProfile == null ? Optional.empty() : activePermissionProfile;
        cwd = cwd == null ? Optional.empty() : cwd;
        workspaceRoots = workspaceRoots == null
            ? Optional.empty()
            : workspaceRoots.map(List::copyOf);
        strictAutoReview = strictAutoReview == null ? Optional.empty() : strictAutoReview;
        legacyPermissionMode = legacyPermissionMode == null ? Optional.empty() : legacyPermissionMode;
    }
}
