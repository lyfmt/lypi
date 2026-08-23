package cn.lycode.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lycode.contracts.runtime.ExecutionRequest;
import cn.lycode.contracts.runtime.ExecutionResult;
import cn.lycode.contracts.runtime.SandboxRuntimePolicy;
import cn.lycode.tool.shell.HostExecutor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShellEnvironmentHarnessTest {
    @TempDir
    Path tempDir;

    private ShellEnvironmentHarness harness() {
        return new ShellEnvironmentHarness(tempDir.resolve("state"));
    }

    @Test
    void sessionDirectoriesUseWorkspaceAndRawSessionHashes() throws Exception {
        ShellEnvironmentHarness harness = harness();
        Path firstWorkspace = Files.createDirectory(tempDir.resolve("workspace-a"));
        Path secondWorkspace = Files.createDirectory(tempDir.resolve("workspace-b"));

        Path slashId = harness.sessionDir(firstWorkspace, "a/b");
        Path underscoreId = harness.sessionDir(firstWorkspace, "a_b");
        Path otherWorkspace = harness.sessionDir(secondWorkspace, "a/b");

        assertTrue(slashId.startsWith(tempDir.resolve("state")));
        assertNotEquals(slashId, underscoreId);
        assertNotEquals(slashId, otherWorkspace);
        assertEquals(64, slashId.getFileName().toString().length());
    }

    @Test
    void snapshotsAreIsolatedByShellAndFilterPwdExports() throws Exception {
        ShellEnvironmentHarness harness = harness();
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));

        ShellEnvironmentHarness.SnapshotPlan bashPlan = harness
            .prepareSnapshot(workspace, "ses_1", "bash")
            .orElseThrow();
        try (bashPlan) {
            Files.writeString(
                bashPlan.captureFile(),
                "declare -x OLDPWD\n"
                    + "declare -x PWD=\"/forged\"\n"
                    + "declare -x KEEP=\"ok\"\n"
                    + "alias ll='ls -l'\n"
                    + "kept_function () { echo ok; }\n"
            );
            harness.completeSnapshot(bashPlan, success());
        }

        assertTrue(harness.snapshotExists(workspace, "ses_1", "bash"));
        assertFalse(harness.snapshotExists(workspace, "ses_1", "sh"));
        String snapshot = Files.readString(bashPlan.snapshotFile());
        assertFalse(snapshot.lines().anyMatch(line -> line.matches("declare -x (OLDPWD|PWD)(=.*)?")));
        assertTrue(snapshot.contains("declare -x KEEP=\"ok\""));
        assertTrue(snapshot.contains("alias ll='ls -l'"));
        assertTrue(snapshot.contains("kept_function"));

        ShellEnvironmentHarness.SnapshotPlan shPlan = harness
            .prepareSnapshot(workspace, "ses_1", "sh")
            .orElseThrow();
        try (shPlan) {
            assertNotEquals(bashPlan.captureFile(), shPlan.captureFile());
            assertNotEquals(bashPlan.snapshotFile(), shPlan.snapshotFile());
            assertEquals("sh", shPlan.command().getFirst());
            Files.writeString(shPlan.captureFile(), "export KEEP='sh'\n");
            harness.completeSnapshot(shPlan, success());
        }
        assertTrue(harness.snapshotExists(workspace, "ses_1", "sh"));
    }

    @Test
    void commandPlanUsesSortedExplicitSourcesAndPosixSyntax() throws Exception {
        ShellEnvironmentHarness harness = harness();
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        createSnapshot(harness, workspace, "ses_1", "sh", "export SNAPSHOT_VALUE='ok'\n");
        Path envDir = harness.sessionDir(workspace, "ses_1").resolve(ShellEnvironmentHarness.ENV_DIR);
        Files.createDirectories(envDir);
        Path second = Files.writeString(envDir.resolve("02-second.sh"), "export SECOND=2\n");
        Path first = Files.writeString(envDir.resolve("01-first.sh"), "export FIRST=1\n");

        try (ShellEnvironmentHarness.CommandPlan plan = harness.prepareCommand(
            workspace,
            "ses_1",
            "sh",
            "printf '%s' \"$SNAPSHOT_VALUE$FIRST$SECOND\"",
            true
        )) {
            assertEquals("sh", plan.command().get(0));
            assertEquals("-c", plan.command().get(1));
            String wrapped = plan.command().get(2);
            assertTrue(wrapped.contains(". '" + plan.snapshotFile() + "'"), wrapped);
            assertTrue(wrapped.indexOf(first.toString()) < wrapped.indexOf(second.toString()), wrapped);
            assertTrue(wrapped.contains("eval 'printf '"), wrapped);
            assertTrue(wrapped.contains("pwd -P >| '" + plan.cwdCaptureFile() + "'"), wrapped);
            assertFalse(wrapped.contains("source "), wrapped);
            assertEquals(List.of(plan.snapshotFile(), first, second), plan.readOnlyFiles());
            assertEquals(List.of(plan.cwdCaptureFile()), plan.writableFiles());
        }
    }

    @Test
    void snapshotCaptureOverridesNoclobberForPrecreatedFile() throws Exception {
        ShellEnvironmentHarness harness = harness();
        Path workspace = Files.createDirectory(tempDir.resolve("workspace-noclobber-snapshot"));
        Path bashEnv = Files.writeString(tempDir.resolve("enable-noclobber.sh"), "set -C\n");
        ShellEnvironmentHarness.SnapshotPlan plan = harness
            .prepareSnapshot(workspace, "ses_noclobber", "bash")
            .orElseThrow();

        try (plan) {
            ExecutionResult result = new HostExecutor().execute(
                new ExecutionRequest(
                    plan.command(),
                    workspace,
                    Map.of("BASH_ENV", bashEnv.toString()),
                    Duration.ofSeconds(5),
                    SandboxRuntimePolicy.disabled()
                ),
                ignored -> {
                },
                () -> false
            );

            assertEquals(0, result.exitCode(), result.stderr());
            harness.completeSnapshot(plan, result);
        }

        assertTrue(harness.snapshotExists(workspace, "ses_noclobber", "bash"));
    }

    @Test
    void nonLoginCommandDoesNotUseOrSourceExistingSnapshot() throws Exception {
        ShellEnvironmentHarness harness = harness();
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        createSnapshot(harness, workspace, "ses_1", "bash", "export SNAPSHOT_VALUE='ok'\n");

        try (ShellEnvironmentHarness.CommandPlan plan = harness.prepareCommand(
            workspace,
            "ses_1",
            "bash",
            "echo hi",
            false
        )) {
            assertEquals("-c", plan.command().get(1));
            assertFalse(plan.command().get(2).contains(plan.snapshotFile().toString()));
            assertFalse(plan.readOnlyFiles().contains(plan.snapshotFile()));
        }
    }

    @Test
    void commandPlansUseUniqueCwdFilesAndConsumeOnlyTheirOwnCapture() throws Exception {
        ShellEnvironmentHarness harness = harness();
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path nested = Files.createDirectory(workspace.resolve("dir with spaces"));

        try (
            ShellEnvironmentHarness.CommandPlan first = harness.prepareCommand(
                workspace,
                "ses_1",
                "bash",
                "pwd",
                false
            );
            ShellEnvironmentHarness.CommandPlan second = harness.prepareCommand(
                workspace,
                "ses_1",
                "bash",
                "pwd",
                false
            )
        ) {
            assertNotEquals(first.cwdCaptureFile(), second.cwdCaptureFile());
            Files.writeString(first.cwdCaptureFile(), nested + "\n");

            assertEquals(Optional.of(nested), harness.consumeCapturedCwd(first, workspace, workspace));
            assertFalse(Files.exists(first.cwdCaptureFile()));
            assertTrue(Files.exists(second.cwdCaptureFile()));
            assertEquals(Optional.empty(), harness.consumeCapturedCwd(second, workspace, workspace));
        }
    }

    @Test
    void capturedCwdRejectsOutsideMissingRelativeAndSymlinkEscapePaths() throws Exception {
        ShellEnvironmentHarness harness = harness();
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Path escape = Files.createSymbolicLink(workspace.resolve("escape"), outside);

        for (String captured : List.of(
            outside.toString(),
            workspace.resolve("missing").toString(),
            "relative",
            escape.toString()
        )) {
            try (ShellEnvironmentHarness.CommandPlan plan = harness.prepareCommand(
                workspace,
                "ses_1",
                "bash",
                "pwd",
                false
            )) {
                Files.writeString(plan.cwdCaptureFile(), captured + "\n");
                assertEquals(Optional.empty(), harness.consumeCapturedCwd(plan, workspace, workspace), captured);
                assertFalse(Files.exists(plan.cwdCaptureFile()));
            }
        }
    }

    @Test
    void importEnvFileCopiesTrustedScriptOnce() throws IOException {
        ShellEnvironmentHarness harness = harness();
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path external = Files.writeString(tempDir.resolve("external.sh"), "export LYCODE_EXTERNAL=1\n");

        harness.importEnvFile(
            workspace,
            "ses_1",
            Map.of(ShellEnvironmentHarness.ENV_FILE_VARIABLE, external.toString())
        );
        Files.writeString(external, "export LYCODE_EXTERNAL=2\n");
        harness.importEnvFile(
            workspace,
            "ses_1",
            Map.of(ShellEnvironmentHarness.ENV_FILE_VARIABLE, external.toString())
        );

        List<Path> scripts = harness.envScripts(workspace, "ses_1");
        assertEquals(1, scripts.size());
        assertEquals("export LYCODE_EXTERNAL=1", Files.readString(scripts.getFirst()).trim());
    }

    private void createSnapshot(
        ShellEnvironmentHarness harness,
        Path workspace,
        String sessionId,
        String shell,
        String content
    ) throws Exception {
        ShellEnvironmentHarness.SnapshotPlan plan = harness
            .prepareSnapshot(workspace, sessionId, shell)
            .orElseThrow();
        try (plan) {
            Files.writeString(plan.captureFile(), content);
            harness.completeSnapshot(plan, success());
        }
    }

    private ExecutionResult success() {
        return new ExecutionResult(0, "", "", false, Optional.empty());
    }
}
