package cn.lycode.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cn.lycode.contracts.runtime.ExecutionRequest;
import cn.lycode.contracts.runtime.ExecutionResult;
import cn.lycode.contracts.runtime.SandboxRuntimePolicy;
import cn.lycode.contracts.tool.ToolResult;
import cn.lycode.contracts.tool.ToolUseContext;
import cn.lycode.tool.shell.BubblewrapExecutor;
import cn.lycode.tool.shell.DefaultSandboxPolicyResolver;
import cn.lycode.tool.shell.SandboxPolicyOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BashToolSandboxSmokeTest {
    @TempDir
    Path tempDir;

    @Test
    void shellStateFilesWorkOutsideWorkspaceInManagedSandbox() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path nested = Files.createDirectory(workspace.resolve("dir with spaces"));
        Path stateRoot = Files.createDirectory(tempDir.resolve("state-root"));
        Path probe = Files.createDirectory(tempDir.resolve("probe"));
        BubblewrapExecutor executor = new BubblewrapExecutor();
        assumeTrue(realBubblewrapWorks(executor, probe), "system bubblewrap is unavailable or cannot create namespaces");

        ShellEnvironmentHarness harness = new ShellEnvironmentHarness(stateRoot);
        Path envSource = Files.writeString(
            tempDir.resolve("session-env.sh"),
            "export LYCODE_TEST_ENV=from-env-script\n"
        );
        harness.importEnvFile(
            workspace,
            "ses_smoke",
            Map.of(ShellEnvironmentHarness.ENV_FILE_VARIABLE, envSource.toString())
        );
        BashTool tool = new BashTool(
            executor,
            new DefaultSandboxPolicyResolver(SandboxPolicyOptions.defaults()),
            harness
        );

        ToolResult<String> result = tool.execute(
            Map.of("command", "cd 'dir with spaces' && printf '%s' \"$LYCODE_TEST_ENV\""),
            new ToolUseContext(
                "ses_smoke",
                "msg_1",
                workspace,
                workspace,
                Map.of("toolUseId", "toolu_1")
            ),
            progress -> {
            }
        );

        assertFalse(result.isError(), result.output());
        assertTrue(result.output().contains("from-env-script"), result.output());
        assertTrue(result.output().contains("sandboxed=true"), result.output());
        assertEquals(nested, result.stateDelta().orElseThrow().cwd());
    }

    private boolean realBubblewrapWorks(BubblewrapExecutor executor, Path cwd) {
        SandboxRuntimePolicy policy = new DefaultSandboxPolicyResolver(SandboxPolicyOptions.defaults())
            .resolve(cwd, cwd);
        ExecutionResult result = executor.execute(
            new ExecutionRequest(
                List.of("bash", "-c", "true"),
                cwd,
                Map.of(),
                Duration.ofSeconds(5),
                policy
            ),
            progress -> {
            },
            () -> false
        );
        return result.exitCode() == 0 && result.metadata().sandboxed();
    }
}
