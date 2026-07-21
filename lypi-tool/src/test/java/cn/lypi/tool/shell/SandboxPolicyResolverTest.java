package cn.lypi.tool.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.security.AdditionalPermissionProfile;
import cn.lypi.contracts.security.FileSystemAccessMode;
import cn.lypi.contracts.security.FileSystemPath;
import cn.lypi.contracts.security.FileSystemPermissionEntry;
import cn.lypi.contracts.security.FileSystemPermissionPolicy;
import cn.lypi.contracts.security.NetworkPermissionPolicy;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.runtime.NetworkMode;
import cn.lypi.contracts.runtime.SandboxRuntimePolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SandboxPolicyResolverTest {
    @TempDir
    Path tempDir;

    @Test
    void resolvesDefaultWorkspaceWritableNetworkDisabledPolicy() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        SandboxPolicyResolver resolver = new DefaultSandboxPolicyResolver(SandboxPolicyOptions.defaults());

        SandboxRuntimePolicy policy = resolver.resolve(workspace, cwd);

        assertTrue(policy.allowRead().contains(Path.of("/usr")));
        assertTrue(policy.allowRead().contains(Path.of("/bin")));
        assertTrue(policy.allowRead().contains(Path.of("/sbin")));
        assertTrue(policy.allowRead().contains(Path.of("/nix/store")));
        assertTrue(policy.allowRead().contains(Path.of("/run/current-system/sw")));
        assertTrue(policy.allowWrite().contains(workspace.toRealPath()));
        assertEquals(NetworkMode.DISABLED, policy.networkMode());
        assertEquals(false, policy.failIfUnavailable());
        assertEquals(false, policy.autoAllowBashIfSandboxed());
    }

    @Test
    void appliesExplicitOptions() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        SandboxPolicyOptions options = new SandboxPolicyOptions(NetworkMode.HOST, true, true);
        SandboxPolicyResolver resolver = new DefaultSandboxPolicyResolver(options);

        SandboxRuntimePolicy policy = resolver.resolve(workspace, workspace);

        assertEquals(NetworkMode.HOST, policy.networkMode());
        assertEquals(true, policy.failIfUnavailable());
        assertEquals(true, policy.autoAllowBashIfSandboxed());
    }

    @Test
    void mergesAdditionalPermissionsForOneDefaultPolicyResolution() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path cache = Files.createDirectory(workspace.resolve("cache"));
        SandboxPolicyResolver resolver = new DefaultSandboxPolicyResolver(SandboxPolicyOptions.defaults());
        AdditionalPermissionProfile additionalPermissions = new AdditionalPermissionProfile(
            Optional.of(FileSystemPermissionPolicy.restricted(List.of(
                new FileSystemPermissionEntry(FileSystemPath.exactPath("cache"), FileSystemAccessMode.WRITE)
            ))),
            Optional.of(NetworkPermissionPolicy.enabled())
        );

        PermissionRuntimeState runtimeState = PermissionRuntimeState.forMode(PermissionMode.BYPASS);
        SandboxRuntimePolicy widened = resolver.resolve(workspace, workspace, runtimeState, additionalPermissions);
        SandboxRuntimePolicy next = resolver.resolve(workspace, workspace, runtimeState);

        assertTrue(widened.allowWrite().contains(cache.toRealPath()));
        assertEquals(NetworkMode.HOST, widened.networkMode());
        assertFalse(next.allowWrite().contains(cache.toRealPath()));
        assertEquals(NetworkMode.DISABLED, next.networkMode());
    }
}
