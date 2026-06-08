package cn.lypi.tool.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.runtime.NetworkMode;
import cn.lypi.contracts.runtime.SandboxRuntimePolicy;
import java.nio.file.Files;
import java.nio.file.Path;
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
        assertTrue(policy.allowWrite().contains(workspace.toRealPath()));
        assertEquals(NetworkMode.DISABLED, policy.networkMode());
        assertEquals(false, policy.failIfUnavailable());
        assertEquals(false, policy.autoAllowBashIfSandboxed());
    }

    @Test
    void appliesExplicitOptions() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        SandboxPolicyOptions options = new SandboxPolicyOptions(NetworkMode.HOST, true);
        SandboxPolicyResolver resolver = new DefaultSandboxPolicyResolver(options);

        SandboxRuntimePolicy policy = resolver.resolve(workspace, workspace);

        assertEquals(NetworkMode.HOST, policy.networkMode());
        assertEquals(true, policy.failIfUnavailable());
        assertEquals(false, policy.autoAllowBashIfSandboxed());
    }
}
