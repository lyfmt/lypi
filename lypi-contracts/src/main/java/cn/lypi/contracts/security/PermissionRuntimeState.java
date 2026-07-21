package cn.lypi.contracts.security;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

public record PermissionRuntimeState(
    ApprovalPolicy approvalPolicy,
    ActivePermissionProfile activePermissionProfile,
    PermissionProfile permissionProfile,
    LegacyPermissionBehavior legacyBehavior,
    PermissionMode legacyPermissionMode
) {
    private static final String READ_ONLY_PROFILE_ID = ":read-only";
    private static final String WORKSPACE_PROFILE_ID = ":workspace";
    private static final String DANGER_FULL_ACCESS_PROFILE_ID = ":danger-full-access";
    private static final String EXTERNAL_PROFILE_ID = ":external";

    @JsonCreator
    public PermissionRuntimeState(
        @JsonProperty("approvalPolicy") ApprovalPolicy approvalPolicy,
        @JsonProperty("activePermissionProfile") ActivePermissionProfile activePermissionProfile,
        @JsonProperty("permissionProfile") PermissionProfile permissionProfile,
        @JsonProperty("legacyBehavior") LegacyPermissionBehavior legacyBehavior,
        @JsonProperty("legacyPermissionMode") PermissionMode legacyPermissionMode
    ) {
        this.approvalPolicy = Objects.requireNonNull(approvalPolicy, "approvalPolicy");
        this.activePermissionProfile = Objects.requireNonNull(activePermissionProfile, "activePermissionProfile");
        this.permissionProfile = permissionProfile == null
            ? builtinProfileOrReadOnly(this.activePermissionProfile.id())
            : permissionProfile;
        this.legacyBehavior = Objects.requireNonNull(legacyBehavior, "legacyBehavior");
        this.legacyPermissionMode = Objects.requireNonNull(legacyPermissionMode, "legacyPermissionMode");
    }

    /**
     * 从公开权限模式派生 canonical runtime state。
     */
    public static PermissionRuntimeState forMode(PermissionMode mode) {
        return switch (Objects.requireNonNull(mode, "mode")) {
            case ASK -> new PermissionRuntimeState(
                ApprovalPolicy.forMode(mode),
                new ActivePermissionProfile(WORKSPACE_PROFILE_ID),
                PermissionProfiles.workspace(),
                new LegacyPermissionBehavior(false, false, true),
                mode
            );
            case AUTO -> new PermissionRuntimeState(
                ApprovalPolicy.forMode(mode),
                new ActivePermissionProfile(WORKSPACE_PROFILE_ID),
                PermissionProfiles.workspace(),
                new LegacyPermissionBehavior(true, false, true),
                mode
            );
            case BYPASS -> new PermissionRuntimeState(
                ApprovalPolicy.forMode(mode),
                new ActivePermissionProfile(DANGER_FULL_ACCESS_PROFILE_ID),
                PermissionProfiles.dangerFullAccess(),
                new LegacyPermissionBehavior(false, true, true),
                mode
            );
        };
    }

    /**
     * 兼容旧数据入口。旧枚举字符串已由 PermissionMode 归一化为三种公开模式。
     */
    public static PermissionRuntimeState fromLegacy(PermissionMode legacyPermissionMode) {
        return forMode(legacyPermissionMode);
    }

    /**
     * 返回权限判定使用的公开模式。
     */
    @JsonIgnore
    public PermissionMode mode() {
        return legacyPermissionMode;
    }

    private static PermissionProfile builtinProfileOrReadOnly(String id) {
        return switch (id) {
            case READ_ONLY_PROFILE_ID -> PermissionProfiles.readOnly();
            case WORKSPACE_PROFILE_ID -> PermissionProfiles.workspace();
            case DANGER_FULL_ACCESS_PROFILE_ID -> PermissionProfiles.dangerFullAccess();
            case EXTERNAL_PROFILE_ID -> PermissionProfiles.external(NetworkPermissionPolicy.restricted());
            default -> PermissionProfiles.readOnly();
        };
    }
}
