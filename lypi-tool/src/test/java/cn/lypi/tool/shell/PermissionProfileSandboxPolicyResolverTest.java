package cn.lypi.tool.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.runtime.NetworkMode;
import cn.lypi.contracts.runtime.SandboxRuntimePolicy;
import cn.lypi.contracts.runtime.SandboxRuntimePolicyKind;
import cn.lypi.contracts.security.AdditionalPermissionProfile;
import cn.lypi.contracts.security.FileSystemAccessMode;
import cn.lypi.contracts.security.FileSystemPath;
import cn.lypi.contracts.security.FileSystemPermissionEntry;
import cn.lypi.contracts.security.FileSystemPermissionPolicy;
import cn.lypi.contracts.security.FileSystemPolicyKind;
import cn.lypi.contracts.security.FileSystemSpecialPath;
import cn.lypi.contracts.security.ManagedPermissionProfile;
import cn.lypi.contracts.security.NetworkPermissionPolicy;
import cn.lypi.contracts.security.PermissionProfiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PermissionProfileSandboxPolicyResolverTest {
    @TempDir
    Path tempDir;

    @Test
    void projectsWorkspaceProfileToManagedSandboxPolicy() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        PermissionProfileSandboxPolicyResolver resolver = new PermissionProfileSandboxPolicyResolver(
            PermissionProfiles.workspace(),
            SandboxPolicyOptions.defaults()
        );

        SandboxRuntimePolicy policy = resolver.resolve(workspace, cwd);

        assertEquals(SandboxRuntimePolicyKind.MANAGED, policy.kind());
        assertTrue(policy.allowRead().contains(Path.of("/")));
        assertTrue(policy.allowRead().contains(workspace.resolve(".git").toAbsolutePath().normalize()));
        assertTrue(policy.allowRead().contains(workspace.resolve(".agents").toAbsolutePath().normalize()));
        assertTrue(policy.allowRead().contains(workspace.resolve(".codex").toAbsolutePath().normalize()));
        assertTrue(policy.allowWrite().contains(workspace.toRealPath()));
        assertTrue(policy.allowWrite().contains(Path.of(System.getProperty("java.io.tmpdir")).toRealPath()));
        assertTrue(policy.allowWrite().contains(Path.of("/tmp").toRealPath()));
        assertEquals(NetworkMode.DISABLED, policy.networkMode());
    }

    @Test
    void projectsDisabledProfileToUnrestrictedHostFallbackPolicy() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        PermissionProfileSandboxPolicyResolver resolver = new PermissionProfileSandboxPolicyResolver(
            PermissionProfiles.dangerFullAccess(),
            SandboxPolicyOptions.defaults()
        );

        SandboxRuntimePolicy policy = resolver.resolve(workspace, workspace);

        assertEquals(SandboxRuntimePolicyKind.DISABLED, policy.kind());
        assertTrue(policy.allowRead().contains(Path.of("/")));
        assertTrue(policy.allowWrite().contains(Path.of("/")));
        assertEquals(NetworkMode.HOST, policy.networkMode());
    }

    @Test
    void projectsExternalProfileToExternalMarkerPolicy() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        PermissionProfileSandboxPolicyResolver resolver = new PermissionProfileSandboxPolicyResolver(
            PermissionProfiles.external(NetworkPermissionPolicy.enabled()),
            SandboxPolicyOptions.defaults()
        );

        SandboxRuntimePolicy policy = resolver.resolve(workspace, workspace);

        assertEquals(SandboxRuntimePolicyKind.EXTERNAL, policy.kind());
        assertTrue(policy.allowRead().isEmpty());
        assertTrue(policy.allowWrite().isEmpty());
        assertEquals(NetworkMode.HOST, policy.networkMode());
    }

    @Test
    void mergesAdditionalExactPathPermissionsForSinglePolicyResolution() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path cache = Files.createDirectory(workspace.resolve("cache"));
        PermissionProfileSandboxPolicyResolver resolver = new PermissionProfileSandboxPolicyResolver(
            PermissionProfiles.readOnly(),
            SandboxPolicyOptions.defaults()
        );
        AdditionalPermissionProfile additionalPermissions = new AdditionalPermissionProfile(
            Optional.of(FileSystemPermissionPolicy.restricted(List.of(
                new FileSystemPermissionEntry(FileSystemPath.exactPath("cache"), FileSystemAccessMode.WRITE)
            ))),
            Optional.empty()
        );

        SandboxRuntimePolicy widened = resolver.resolve(workspace, workspace, additionalPermissions);
        SandboxRuntimePolicy next = resolver.resolve(workspace, workspace);

        assertTrue(widened.allowRead().contains(Path.of("/")));
        assertTrue(widened.allowWrite().contains(cache.toRealPath()));
        assertFalse(next.allowWrite().contains(cache.toRealPath()));
    }

    @Test
    void additionalRestrictedNetworkDoesNotNarrowManagedNetworkPolicy() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        ManagedPermissionProfile profile = new ManagedPermissionProfile(
            FileSystemPermissionPolicy.restricted(List.of()),
            NetworkPermissionPolicy.enabled()
        );
        PermissionProfileSandboxPolicyResolver resolver = new PermissionProfileSandboxPolicyResolver(
            profile,
            SandboxPolicyOptions.defaults()
        );
        AdditionalPermissionProfile additionalPermissions = new AdditionalPermissionProfile(
            Optional.empty(),
            Optional.of(NetworkPermissionPolicy.restricted())
        );

        SandboxRuntimePolicy policy = resolver.resolve(workspace, workspace, additionalPermissions);

        assertEquals(NetworkMode.HOST, policy.networkMode());
    }

    @Test
    void rejectsUnsupportedAdditionalPermissionProjection() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        PermissionProfileSandboxPolicyResolver resolver = new PermissionProfileSandboxPolicyResolver(
            PermissionProfiles.workspace(),
            SandboxPolicyOptions.defaults()
        );
        AdditionalPermissionProfile unrestrictedAdditionalPermissions = new AdditionalPermissionProfile(
            Optional.of(new FileSystemPermissionPolicy(FileSystemPolicyKind.UNRESTRICTED, List.of())),
            Optional.empty()
        );
        AdditionalPermissionProfile globAdditionalPermissions = new AdditionalPermissionProfile(
            Optional.of(FileSystemPermissionPolicy.restricted(List.of(
                new FileSystemPermissionEntry(FileSystemPath.globPattern("build/**"), FileSystemAccessMode.READ)
            ))),
            Optional.empty()
        );
        AdditionalPermissionProfile denyAdditionalPermissions = new AdditionalPermissionProfile(
            Optional.of(FileSystemPermissionPolicy.restricted(List.of(
                new FileSystemPermissionEntry(FileSystemPath.exactPath("secret"), FileSystemAccessMode.DENY)
            ))),
            Optional.empty()
        );

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(workspace, workspace, unrestrictedAdditionalPermissions));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(workspace, workspace, globAdditionalPermissions));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(workspace, workspace, denyAdditionalPermissions));
    }

    @Test
    void projectsRestrictedExactPathProfileEntries() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path readme = Files.createFile(workspace.resolve("README.md"));
        Path output = Files.createDirectory(workspace.resolve("out"));
        ManagedPermissionProfile profile = new ManagedPermissionProfile(
            FileSystemPermissionPolicy.restricted(List.of(
                new FileSystemPermissionEntry(FileSystemPath.exactPath("README.md"), FileSystemAccessMode.READ),
                new FileSystemPermissionEntry(FileSystemPath.exactPath("out"), FileSystemAccessMode.WRITE),
                new FileSystemPermissionEntry(FileSystemPath.special(FileSystemSpecialPath.ROOT), FileSystemAccessMode.READ)
            )),
            NetworkPermissionPolicy.restricted()
        );
        PermissionProfileSandboxPolicyResolver resolver = new PermissionProfileSandboxPolicyResolver(
            profile,
            SandboxPolicyOptions.defaults()
        );

        SandboxRuntimePolicy policy = resolver.resolve(workspace, workspace);

        assertTrue(policy.allowRead().contains(readme.toRealPath()));
        assertTrue(policy.allowRead().contains(Path.of("/")));
        assertTrue(policy.allowWrite().contains(output.toRealPath()));
    }
}
