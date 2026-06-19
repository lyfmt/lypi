package cn.lypi.security;

import cn.lypi.contracts.security.ActivePermissionProfile;
import cn.lypi.contracts.security.ApprovalPolicy;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionProfile;
import cn.lypi.contracts.security.PermissionProfiles;
import cn.lypi.contracts.security.PermissionRuntimeOverrides;
import cn.lypi.contracts.security.PermissionRuntimeState;
import java.util.List;
import java.util.Objects;

/**
 * 合并权限 runtime state override。
 */
public final class PermissionRuntimeStateResolver {
    /**
     * 按顺序合并 override，并在 turn 结束时清理 turn-scoped 状态。
     */
    public ResolvedRuntimeState resolve(
        PermissionRuntimeState bootDefault,
        List<PermissionRuntimeOverrides> overrides,
        boolean turnEnded
    ) {
        PermissionRuntimeState state = Objects.requireNonNull(bootDefault, "bootDefault");
        boolean strictAutoReview = false;
        List<PermissionRuntimeOverrides> safeOverrides = overrides == null ? List.of() : List.copyOf(overrides);
        for (PermissionRuntimeOverrides override : safeOverrides) {
            if (override == null) {
                continue;
            }
            if (override.legacyPermissionMode().isPresent()) {
                state = PermissionRuntimeState.fromLegacy(override.legacyPermissionMode().orElseThrow());
            }
            ApprovalPolicy approvalPolicy = override.approvalPolicy().orElse(state.approvalPolicy());
            ActivePermissionProfile activePermissionProfile = override.activePermissionProfile()
                .orElse(state.activePermissionProfile());
            PermissionProfile permissionProfile = override.activePermissionProfile().isPresent()
                ? builtinProfileOrReadOnly(activePermissionProfile.id())
                : state.permissionProfile();
            PermissionMode legacyPermissionMode = override.legacyPermissionMode().orElse(state.legacyPermissionMode());
            state = new PermissionRuntimeState(
                approvalPolicy,
                activePermissionProfile,
                permissionProfile,
                state.legacyBehavior(),
                legacyPermissionMode
            );
            if (override.strictAutoReview().isPresent()) {
                strictAutoReview = override.strictAutoReview().orElseThrow();
            }
        }
        return new ResolvedRuntimeState(state, turnEnded ? false : strictAutoReview);
    }

    /**
     * 为 child session spawn 创建不继承 turn-scoped 状态的结果。
     */
    public ResolvedRuntimeState forChildSpawn(ResolvedRuntimeState parent) {
        ResolvedRuntimeState safeParent = Objects.requireNonNull(parent, "parent");
        return new ResolvedRuntimeState(safeParent.permissionRuntimeState(), false);
    }

    private PermissionProfile builtinProfileOrReadOnly(String id) {
        return switch (id) {
            case ":read-only" -> PermissionProfiles.readOnly();
            case ":workspace" -> PermissionProfiles.workspace();
            case ":danger-full-access" -> PermissionProfiles.dangerFullAccess();
            case ":external" -> PermissionProfiles.external(cn.lypi.contracts.security.NetworkPermissionPolicy.restricted());
            default -> PermissionProfiles.readOnly();
        };
    }

    public record ResolvedRuntimeState(
        PermissionRuntimeState permissionRuntimeState,
        boolean strictAutoReview
    ) {
        public ResolvedRuntimeState {
            permissionRuntimeState = Objects.requireNonNull(permissionRuntimeState, "permissionRuntimeState");
        }
    }
}
