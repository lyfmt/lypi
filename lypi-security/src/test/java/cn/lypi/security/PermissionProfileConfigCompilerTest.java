package cn.lypi.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lypi.contracts.security.ActivePermissionProfile;
import cn.lypi.contracts.security.FileSystemAccessMode;
import cn.lypi.contracts.security.FileSystemPath;
import cn.lypi.contracts.security.FileSystemPermissionEntry;
import cn.lypi.contracts.security.FileSystemPermissionPolicy;
import cn.lypi.contracts.security.FileSystemSpecialPath;
import cn.lypi.contracts.security.FileSystemPolicyKind;
import cn.lypi.contracts.security.ExternalPermissionProfile;
import cn.lypi.contracts.security.ManagedPermissionProfile;
import cn.lypi.contracts.security.NetworkPermissionPolicy;
import cn.lypi.contracts.security.NetworkPolicyMode;
import cn.lypi.contracts.security.PermissionProfileConfig;
import cn.lypi.contracts.security.PermissionProfileSelection;
import cn.lypi.contracts.security.PermissionProfiles;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PermissionProfileConfigCompilerTest {
    private final PermissionProfileConfigCompiler compiler = new PermissionProfileConfigCompiler();

    @Test
    void compilesBuiltinReadOnlyProfileSelection() {
        PermissionProfileSelection selection = compiler.compile(Map.of(), ":read-only");

        assertThat(selection.activePermissionProfile()).isEqualTo(new ActivePermissionProfile(":read-only"));
        assertThat(selection.permissionProfile()).isEqualTo(PermissionProfiles.readOnly());
        assertThat(selection.permissionProfile().network().mode()).isEqualTo(NetworkPolicyMode.RESTRICTED);
    }

    @Test
    void compilesBuiltinWorkspaceProfileSelection() {
        PermissionProfileSelection selection = compiler.compile(Map.of(), ":workspace");

        assertThat(selection.activePermissionProfile()).isEqualTo(new ActivePermissionProfile(":workspace"));
        assertThat(selection.permissionProfile()).isEqualTo(PermissionProfiles.workspace());
    }

    @Test
    void compilesBuiltinDangerFullAccessProfileSelection() {
        PermissionProfileSelection selection = compiler.compile(Map.of(), ":danger-full-access");

        assertThat(selection.activePermissionProfile()).isEqualTo(new ActivePermissionProfile(":danger-full-access"));
        assertThat(selection.permissionProfile().fileSystem().kind()).isEqualTo(FileSystemPolicyKind.UNRESTRICTED);
        assertThat(selection.permissionProfile().network().mode()).isEqualTo(NetworkPolicyMode.ENABLED);
    }

    @Test
    void compilesBuiltinExternalProfileSelection() {
        PermissionProfileSelection selection = compiler.compile(Map.of(), ":external");

        assertThat(selection.activePermissionProfile()).isEqualTo(new ActivePermissionProfile(":external"));
        assertThat(selection.permissionProfile()).isInstanceOf(ExternalPermissionProfile.class);
        assertThat(selection.permissionProfile().fileSystem().kind()).isEqualTo(FileSystemPolicyKind.EXTERNAL_SANDBOX);
        assertThat(selection.permissionProfile().network().mode()).isEqualTo(NetworkPolicyMode.RESTRICTED);
    }

    @Test
    void compilesCustomProfileExtendingWorkspaceAndKeepsInheritanceIdentity() {
        PermissionProfileConfig custom = new PermissionProfileConfig(
            "Dev profile",
            Optional.of(":workspace"),
            List.of(),
            Optional.of(FileSystemPermissionPolicy.restricted(List.of(
                new FileSystemPermissionEntry(FileSystemPath.special(FileSystemSpecialPath.ROOT), FileSystemAccessMode.READ),
                new FileSystemPermissionEntry(FileSystemPath.special(FileSystemSpecialPath.PROJECT_ROOTS), FileSystemAccessMode.WRITE),
                new FileSystemPermissionEntry(FileSystemPath.exactPath("/tmp/cache"), FileSystemAccessMode.WRITE)
            ))),
            Optional.empty()
        );

        PermissionProfileSelection selection = compiler.compile(Map.of("dev", custom), "dev");

        assertThat(selection.activePermissionProfile())
            .isEqualTo(new ActivePermissionProfile("dev", Optional.of(":workspace")));
        assertThat(selection.permissionProfile()).isInstanceOf(ManagedPermissionProfile.class);
        ManagedPermissionProfile profile = (ManagedPermissionProfile) selection.permissionProfile();
        assertThat(profile.fileSystem().entries())
            .contains(new FileSystemPermissionEntry(FileSystemPath.exactPath("/tmp/cache"), FileSystemAccessMode.WRITE));
        assertThat(profile.network().mode()).isEqualTo(NetworkPolicyMode.RESTRICTED);
    }

    @Test
    void customProfileWithoutExtendsStartsFromFailClosedReadOnlyBaseline() {
        PermissionProfileConfig custom = new PermissionProfileConfig(
            "Network only",
            Optional.empty(),
            List.of(),
            Optional.empty(),
            Optional.of(NetworkPermissionPolicy.enabled())
        );

        PermissionProfileSelection selection = compiler.compile(Map.of("network-only", custom), "network-only");

        ManagedPermissionProfile profile = (ManagedPermissionProfile) selection.permissionProfile();
        assertThat(profile.fileSystem()).isEqualTo(PermissionProfiles.readOnly().fileSystem());
        assertThat(profile.fileSystem().entries())
            .doesNotContain(new FileSystemPermissionEntry(
                FileSystemPath.special(FileSystemSpecialPath.PROJECT_ROOTS),
                FileSystemAccessMode.WRITE
            ));
        assertThat(profile.network().mode()).isEqualTo(NetworkPolicyMode.ENABLED);
        assertThat(selection.activePermissionProfile())
            .isEqualTo(new ActivePermissionProfile("network-only", Optional.empty()));
    }

    @Test
    void childOverridesParentFilesystemAndNetworkPolicy() {
        PermissionProfileConfig parent = new PermissionProfileConfig(
            "Parent",
            Optional.of(":workspace"),
            List.of(),
            Optional.of(FileSystemPermissionPolicy.restricted(List.of(
                new FileSystemPermissionEntry(FileSystemPath.exactPath("/parent"), FileSystemAccessMode.WRITE)
            ))),
            Optional.of(NetworkPermissionPolicy.enabled())
        );
        PermissionProfileConfig child = new PermissionProfileConfig(
            "Child",
            Optional.of("parent"),
            List.of(),
            Optional.of(FileSystemPermissionPolicy.restricted(List.of(
                new FileSystemPermissionEntry(FileSystemPath.exactPath("/child"), FileSystemAccessMode.READ)
            ))),
            Optional.of(NetworkPermissionPolicy.restricted())
        );

        PermissionProfileSelection selection = compiler.compile(Map.of(
            "parent", parent,
            "child", child
        ), "child");

        assertThat(selection.permissionProfile()).isInstanceOf(ManagedPermissionProfile.class);
        ManagedPermissionProfile profile = (ManagedPermissionProfile) selection.permissionProfile();
        assertThat(profile.fileSystem().entries())
            .containsExactly(new FileSystemPermissionEntry(FileSystemPath.exactPath("/child"), FileSystemAccessMode.READ));
        assertThat(profile.network().mode()).isEqualTo(NetworkPolicyMode.RESTRICTED);
        assertThat(selection.activePermissionProfile())
            .isEqualTo(new ActivePermissionProfile("child", Optional.of("parent")));
    }

    @Test
    void unknownBuiltinProfileFailsClosed() {
        assertThatThrownBy(() -> compiler.compile(Map.of(), ":missing"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown built-in permission profile");
    }

    @Test
    void customProfileCannotUseReservedColonPrefix() {
        PermissionProfileConfig custom = new PermissionProfileConfig(
            "Bad",
            Optional.of(":workspace"),
            List.of(),
            Optional.empty(),
            Optional.empty()
        );

        assertThatThrownBy(() -> compiler.compile(Map.of(":bad", custom), ":bad"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not start with ':'");
    }

    @Test
    void customProfileCannotExtendDangerFullAccess() {
        PermissionProfileConfig custom = new PermissionProfileConfig(
            "Unsafe",
            Optional.of(":danger-full-access"),
            List.of(),
            Optional.empty(),
            Optional.empty()
        );

        assertThatThrownBy(() -> compiler.compile(Map.of("unsafe", custom), "unsafe"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported parent built-in permission profile");
    }

    @Test
    void customProfileCannotExtendExternal() {
        PermissionProfileConfig custom = new PermissionProfileConfig(
            "External wrapper",
            Optional.of(":external"),
            List.of(),
            Optional.empty(),
            Optional.empty()
        );

        assertThatThrownBy(() -> compiler.compile(Map.of("external-wrapper", custom), "external-wrapper"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported parent built-in permission profile");
    }

    @Test
    void customProfileCannotDeclareUnrestrictedFilesystemPolicy() {
        PermissionProfileConfig custom = new PermissionProfileConfig(
            "Unrestricted wrapper",
            Optional.of(":read-only"),
            List.of(),
            Optional.of(FileSystemPermissionPolicy.unrestricted()),
            Optional.empty()
        );

        assertThatThrownBy(() -> compiler.compile(Map.of("unrestricted-wrapper", custom), "unrestricted-wrapper"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Custom permission profile filesystem policy must be restricted");
    }

    @Test
    void customProfileCannotDeclareExternalFilesystemPolicy() {
        PermissionProfileConfig custom = new PermissionProfileConfig(
            "External filesystem wrapper",
            Optional.of(":read-only"),
            List.of(),
            Optional.of(FileSystemPermissionPolicy.externalSandbox()),
            Optional.empty()
        );

        assertThatThrownBy(() -> compiler.compile(Map.of("external-fs-wrapper", custom), "external-fs-wrapper"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Custom permission profile filesystem policy must be restricted");
    }

    @Test
    void extendsCycleFailsClosed() {
        PermissionProfileConfig first = new PermissionProfileConfig(
            "First",
            Optional.of("second"),
            List.of(),
            Optional.empty(),
            Optional.empty()
        );
        PermissionProfileConfig second = new PermissionProfileConfig(
            "Second",
            Optional.of("first"),
            List.of(),
            Optional.empty(),
            Optional.empty()
        );

        assertThatThrownBy(() -> compiler.compile(Map.of("first", first, "second", second), "first"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cycle");
    }

    @Test
    void workspaceRootsCompileToWritableExactPaths() {
        PermissionProfileConfig custom = new PermissionProfileConfig(
            "Workspace roots",
            Optional.of(":read-only"),
            List.of(Path.of("/workspace/app"), Path.of("/workspace/lib")),
            Optional.empty(),
            Optional.empty()
        );

        PermissionProfileSelection selection = compiler.compile(Map.of("workspace-roots", custom), "workspace-roots");

        ManagedPermissionProfile profile = (ManagedPermissionProfile) selection.permissionProfile();
        assertThat(profile.fileSystem().entries()).contains(
            new FileSystemPermissionEntry(FileSystemPath.exactPath("/workspace/app"), FileSystemAccessMode.WRITE),
            new FileSystemPermissionEntry(FileSystemPath.exactPath("/workspace/lib"), FileSystemAccessMode.WRITE)
        );
        assertThat(profile.fileSystem().entries())
            .contains(new FileSystemPermissionEntry(FileSystemPath.special(FileSystemSpecialPath.ROOT), FileSystemAccessMode.READ));
    }
}
