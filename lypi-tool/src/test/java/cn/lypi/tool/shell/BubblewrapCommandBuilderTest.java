package cn.lypi.tool.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.runtime.ExecutionRequest;
import cn.lypi.contracts.runtime.NetworkMode;
import cn.lypi.contracts.runtime.SandboxRuntimePolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BubblewrapCommandBuilderTest {
    @TempDir
    Path tempDir;

    @Test
    void buildsMinimalNetworkDisabledBwrapArgv() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        ExecutionRequest request = request(cwd, policy(workspace, NetworkMode.DISABLED));

        List<String> argv = BubblewrapCommandBuilder.defaults().build(request);

        assertEquals("bwrap", argv.getFirst());
        assertTrue(argv.contains("--new-session"));
        assertTrue(argv.contains("--die-with-parent"));
        assertTrue(argv.contains("--unshare-user"));
        assertTrue(argv.contains("--unshare-pid"));
        assertTrue(argv.contains("--unshare-net"));
        assertContainsSequence(argv, "--ro-bind-try", "/usr", "/usr");
        assertContainsSequence(argv, "--ro-bind-try", "/bin", "/bin");
        assertContainsSequence(argv, "--ro-bind-try", "/lib", "/lib");
        assertContainsSequence(argv, "--ro-bind-try", "/lib64", "/lib64");
        assertContainsSequence(argv, "--ro-bind-try", "/etc", "/etc");
        assertContainsSequence(argv, "--dev", "/dev");
        assertContainsSequence(argv, "--proc", "/proc");
        assertTrue(indexOfSequence(argv, "--tmpfs", "/tmp") < indexOfSequence(argv, "--bind", workspace.toString(), workspace.toString()));
        assertContainsSequence(argv, "--bind", workspace.toString(), workspace.toString());
        assertContainsSequence(argv, "--tmpfs", "/tmp");
        assertContainsSequence(argv, "--chdir", cwd.toString());
        assertCommandSuffix(argv, List.of("bash", "-lc", "printf hello"));
    }

    @Test
    void omitsNetworkUnshareForHostNetworkMode() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        ExecutionRequest request = request(cwd, policy(workspace, NetworkMode.HOST));

        List<String> argv = BubblewrapCommandBuilder.defaults().build(request);

        assertTrue(!argv.contains("--unshare-net"));
    }

    @Test
    void canOmitProcMountWhenRequested() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        ExecutionRequest request = request(cwd, policy(workspace, NetworkMode.DISABLED));

        List<String> argv = BubblewrapCommandBuilder.defaults()
            .build(request, new BubblewrapCommandBuilder.Options(false));

        assertTrue(!containsSequence(argv, "--proc", "/proc"));
        assertContainsSequence(argv, "--dev", "/dev");
        assertContainsSequence(argv, "--bind", workspace.toString(), workspace.toString());
        assertCommandSuffix(argv, List.of("bash", "-lc", "printf hello"));
    }

    @Test
    void rejectsUnsupportedDenyPathsInsteadOfIgnoringThem() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path cwd = Files.createDirectory(workspace.resolve("src"));
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/usr")),
            List.of(workspace.resolve("secret")),
            List.of(workspace),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> BubblewrapCommandBuilder.defaults().build(request(cwd, policy))
        );

        assertTrue(exception.getMessage().contains("denyRead"));
        assertTrue(exception.getMessage().contains("unsupported"));
    }

    @Test
    void rejectsRelativeMountPaths() {
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("relative-read")),
            List.of(),
            List.of(Path.of("relative-write")),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> BubblewrapCommandBuilder.defaults().build(request(Path.of("relative-cwd"), policy))
        );

        assertTrue(exception.getMessage().contains("absolute"));
    }

    private ExecutionRequest request(Path cwd, SandboxRuntimePolicy policy) {
        return new ExecutionRequest(
            List.of("bash", "-lc", "printf hello"),
            cwd,
            Map.of(),
            Duration.ofSeconds(5),
            policy
        );
    }

    private SandboxRuntimePolicy policy(Path workspace, NetworkMode networkMode) {
        return new SandboxRuntimePolicy(
            List.of(Path.of("/usr"), Path.of("/bin"), Path.of("/lib"), Path.of("/lib64"), Path.of("/etc")),
            List.of(),
            List.of(workspace),
            List.of(),
            networkMode,
            false,
            false
        );
    }

    private void assertContainsSequence(List<String> argv, String... sequence) {
        if (containsSequence(argv, sequence)) {
            return;
        }
        throw new AssertionError("missing sequence " + List.of(sequence) + " in " + argv);
    }

    private boolean containsSequence(List<String> argv, String... sequence) {
        return indexOfSequence(argv, sequence) >= 0;
    }

    private int indexOfSequence(List<String> argv, String... sequence) {
        for (int index = 0; index <= argv.size() - sequence.length; index++) {
            boolean matches = true;
            for (int offset = 0; offset < sequence.length; offset++) {
                matches = matches && sequence[offset].equals(argv.get(index + offset));
            }
            if (matches) {
                return index;
            }
        }
        return -1;
    }

    private void assertCommandSuffix(List<String> argv, List<String> command) {
        int separator = argv.lastIndexOf("--");
        assertTrue(separator >= 0, "bwrap argv must contain -- separator");
        assertEquals(command, argv.subList(separator + 1, argv.size()));
    }
}
