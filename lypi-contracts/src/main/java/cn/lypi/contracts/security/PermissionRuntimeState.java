package cn.lypi.contracts.security;

import java.util.Objects;

public record PermissionRuntimeState(
    ApprovalPolicy approvalPolicy,
    ActivePermissionProfile activePermissionProfile,
    LegacyPermissionBehavior legacyBehavior,
    PermissionMode legacyPermissionMode
) {
    private static final String WORKSPACE_PROFILE_ID = ":workspace";
    private static final String DANGER_FULL_ACCESS_PROFILE_ID = ":danger-full-access";

    public PermissionRuntimeState {
        approvalPolicy = Objects.requireNonNull(approvalPolicy, "approvalPolicy");
        activePermissionProfile = Objects.requireNonNull(activePermissionProfile, "activePermissionProfile");
        legacyBehavior = Objects.requireNonNull(legacyBehavior, "legacyBehavior");
        legacyPermissionMode = Objects.requireNonNull(legacyPermissionMode, "legacyPermissionMode");
    }

    /**
     * 从旧权限模式派生 canonical runtime state。
     *
     * NOTE: 新代码不得把 PermissionMode 作为权限判定依据。
     */
    public static PermissionRuntimeState fromLegacy(PermissionMode legacyPermissionMode) {
        return switch (Objects.requireNonNull(legacyPermissionMode, "legacyPermissionMode")) {
            case DEFAULT_EXECUTE -> new PermissionRuntimeState(
                ApprovalPolicy.fromLegacy(legacyPermissionMode),
                new ActivePermissionProfile(WORKSPACE_PROFILE_ID),
                new LegacyPermissionBehavior(false, false, true),
                legacyPermissionMode
            );
            case ACCEPT_EDITS -> new PermissionRuntimeState(
                ApprovalPolicy.fromLegacy(legacyPermissionMode),
                new ActivePermissionProfile(WORKSPACE_PROFILE_ID),
                new LegacyPermissionBehavior(true, false, true),
                legacyPermissionMode
            );
            case BYPASS -> new PermissionRuntimeState(
                ApprovalPolicy.fromLegacy(legacyPermissionMode),
                new ActivePermissionProfile(DANGER_FULL_ACCESS_PROFILE_ID),
                new LegacyPermissionBehavior(false, true, true),
                legacyPermissionMode
            );
        };
    }
}
