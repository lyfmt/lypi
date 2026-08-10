package cn.lypi.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cn.lypi.contracts.runtime.ExecutionRequest;
import cn.lypi.contracts.runtime.ExecutionResult;
import cn.lypi.contracts.runtime.SandboxRuntimePolicy;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.tool.shell.BubblewrapExecutor;
import cn.lypi.tool.shell.DefaultSandboxPolicyResolver;
import cn.lypi.tool.shell.SandboxPolicyOptions;
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
            "export LYPI_TEST_ENV=from-env-script\n"
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
            Map.of("command", "cd 'dir with spaces' && printf '%s' \"$LYPI_TEST_ENV\""),
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
