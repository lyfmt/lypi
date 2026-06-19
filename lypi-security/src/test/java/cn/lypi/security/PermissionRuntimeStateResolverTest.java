package cn.lypi.security;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.security.ActivePermissionProfile;
import cn.lypi.contracts.security.ApprovalMode;
import cn.lypi.contracts.security.ApprovalPolicy;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeOverrides;
import cn.lypi.contracts.security.PermissionRuntimeState;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PermissionRuntimeStateResolverTest {
    private final PermissionRuntimeStateResolver resolver = new PermissionRuntimeStateResolver();

    @Test
    void startsFromBootDefaultState() {
        PermissionRuntimeState base = PermissionRuntimeState.fromLegacy(PermissionMode.ACCEPT_EDITS);

        PermissionRuntimeStateResolver.ResolvedRuntimeState resolved = resolver.resolve(
            base,
            List.of(),
            false
        );

        assertThat(resolved.permissionRuntimeState()).isEqualTo(base);
        assertThat(resolved.strictAutoReview()).isFalse();
    }

    @Test
    void laterOverridesReplaceEarlierRuntimeFields() {
        PermissionRuntimeOverrides sessionOverride = override(
            Optional.of(new ApprovalPolicy(ApprovalMode.NEVER)),
            Optional.empty(),
            Optional.empty()
        );
        PermissionRuntimeOverrides turnOverride = override(
            Optional.empty(),
            Optional.of(new ActivePermissionProfile(":read-only")),
            Optional.of(true)
        );

        PermissionRuntimeStateResolver.ResolvedRuntimeState resolved = resolver.resolve(
            PermissionRuntimeState.fromLegacy(PermissionMode.DEFAULT_EXECUTE),
            List.of(sessionOverride, turnOverride),
            false
        );

        assertThat(resolved.permissionRuntimeState().approvalPolicy().mode()).isEqualTo(ApprovalMode.NEVER);
        assertThat(resolved.permissionRuntimeState().activePermissionProfile().id()).isEqualTo(":read-only");
        assertThat(resolved.strictAutoReview()).isTrue();
    }

    @Test
    void legacyModeOverrideRecomputesCanonicalRuntimeState() {
        PermissionRuntimeOverrides legacyOverride = new PermissionRuntimeOverrides(
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(PermissionMode.BYPASS)
        );

        PermissionRuntimeStateResolver.ResolvedRuntimeState resolved = resolver.resolve(
            PermissionRuntimeState.fromLegacy(PermissionMode.DEFAULT_EXECUTE),
            List.of(legacyOverride),
            false
        );

        assertThat(resolved.permissionRuntimeState()).isEqualTo(PermissionRuntimeState.fromLegacy(PermissionMode.BYPASS));
    }

    @Test
    void replayAndCompactionDoNotRestoreEndedTurnStrictAutoReview() {
        PermissionRuntimeOverrides turnOverride = override(
            Optional.empty(),
            Optional.empty(),
            Optional.of(true)
        );

        PermissionRuntimeStateResolver.ResolvedRuntimeState resolved = resolver.resolve(
            PermissionRuntimeState.fromLegacy(PermissionMode.DEFAULT_EXECUTE),
            List.of(turnOverride),
            true
        );

        assertThat(resolved.strictAutoReview()).isFalse();
    }

    @Test
    void explicitContinueOverrideCanClearStrictAutoReview() {
        PermissionRuntimeStateResolver.ResolvedRuntimeState resolved = resolver.resolve(
            PermissionRuntimeState.fromLegacy(PermissionMode.DEFAULT_EXECUTE),
            List.of(
                override(Optional.empty(), Optional.empty(), Optional.of(true)),
                override(Optional.empty(), Optional.empty(), Optional.of(false))
            ),
            false
        );

        assertThat(resolved.strictAutoReview()).isFalse();
    }

    @Test
    void childSessionSpawnDoesNotInheritTurnScopedStrictAutoReview() {
        PermissionRuntimeStateResolver.ResolvedRuntimeState parent = resolver.resolve(
            PermissionRuntimeState.fromLegacy(PermissionMode.DEFAULT_EXECUTE),
            List.of(override(Optional.empty(), Optional.empty(), Optional.of(true))),
            false
        );

        PermissionRuntimeStateResolver.ResolvedRuntimeState child = resolver.forChildSpawn(parent);

        assertThat(parent.strictAutoReview()).isTrue();
        assertThat(child.permissionRuntimeState()).isEqualTo(parent.permissionRuntimeState());
        assertThat(child.strictAutoReview()).isFalse();
    }

    private PermissionRuntimeOverrides override(
        Optional<ApprovalPolicy> approvalPolicy,
        Optional<ActivePermissionProfile> activePermissionProfile,
        Optional<Boolean> strictAutoReview
    ) {
        return new PermissionRuntimeOverrides(
            approvalPolicy,
            activePermissionProfile,
            Optional.of(Path.of("/workspace")),
            Optional.of(List.of(Path.of("/workspace"))),
            strictAutoReview,
            Optional.empty()
        );
    }
}
