package cn.lypi.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShellEnvironmentHarnessTest {
    @TempDir
    Path stateRoot;

    private ShellEnvironmentHarness harness() {
        return new ShellEnvironmentHarness(stateRoot);
    }

    @Test
    void wrapWithoutSnapshotSourcesEnvDirAndCapturesCwd() {
        ShellEnvironmentHarness harness = harness();

        String wrapped = harness.wrap("ses_1", "echo hi");

        assertFalse(wrapped.contains("shell-snapshot.sh"));
        assertTrue(wrapped.contains("eval 'echo hi'"));
        assertTrue(wrapped.contains("pwd -P >|"));
        assertTrue(wrapped.contains("exit $lypi_rc"));
        assertTrue(wrapped.contains("env"));
    }

    @Test
    void wrapWithSnapshotSourcesSnapshotFirst() throws IOException {
        ShellEnvironmentHarness harness = harness();
        Path dir = harness.sessionDir("ses_1");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(ShellEnvironmentHarness.SNAPSHOT_FILE), "export FOO=1\n");

        String wrapped = harness.wrap("ses_1", "echo hi");

        assertTrue(wrapped.startsWith("source '"));
        assertTrue(wrapped.contains("shell-snapshot.sh"));
        assertTrue(wrapped.contains("|| true && "));
        assertTrue(harness.snapshotExists("ses_1"));
    }

    @Test
    void ensureSnapshotDumpsEnvironmentOnce() {
        ShellEnvironmentHarness harness = harness();

        harness.ensureSnapshot("ses_1", "bash");
        assertTrue(harness.snapshotExists("ses_1"));

        // 第二次调用不重写（mtime 不变）
        Path snapshot = harness.sessionDir("ses_1").resolve(ShellEnvironmentHarness.SNAPSHOT_FILE);
        try {
            long mtime = Files.getLastModifiedTime(snapshot).toMillis();
            harness.ensureSnapshot("ses_1", "bash");
            assertEquals(mtime, Files.getLastModifiedTime(snapshot).toMillis());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void wrappedCommandExecutesWithEnvScriptsAndCapturesCwd() throws Exception {
        ShellEnvironmentHarness harness = harness();
        Path envDir = harness.sessionDir("ses_2").resolve(ShellEnvironmentHarness.ENV_DIR);
        Files.createDirectories(envDir);
        Files.writeString(envDir.resolve("01-first.sh"), "export LYPI_TEST_A=hello\n");
        Files.writeString(envDir.resolve("02-second.sh"), "export LYPI_TEST_B=$LYPI_TEST_A-world\n");

        List<Path> scripts = harness.envScripts("ses_2").toList();
        assertEquals(2, scripts.size());
        assertTrue(scripts.get(0).getFileName().toString().startsWith("01"));

        String wrapped = harness.wrap("ses_2", "echo \"$LYPI_TEST_B\" && cd /");
        Process process = new ProcessBuilder("bash", "-c", wrapped)
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor());
        assertTrue(output.contains("hello-world"), output);

        Optional<Path> cwd = harness.consumeCapturedCwd("ses_2");
        assertEquals(Optional.of(Path.of("/")), cwd);
        // 已消费，再次读取为空
        assertTrue(harness.consumeCapturedCwd("ses_2").isEmpty());
    }

    @Test
    void importEnvFileCopiesExternalScriptOnce() throws IOException {
        ShellEnvironmentHarness harness = harness();
        Path external = stateRoot.resolve("external.sh");
        Files.writeString(external, "export LYPI_EXTERNAL=1\n");

        harness.importEnvFile("ses_3", Map.of(ShellEnvironmentHarness.ENV_FILE_VARIABLE, external.toString()));
        harness.importEnvFile("ses_3", Map.of(ShellEnvironmentHarness.ENV_FILE_VARIABLE, external.toString()));

        List<Path> scripts = harness.envScripts("ses_3").toList();
        assertEquals(1, scripts.size());
        assertEquals("export LYPI_EXTERNAL=1", Files.readString(scripts.get(0)).trim());
    }

    @Test
    void sessionIdSanitizedForFilesystem() {
        ShellEnvironmentHarness harness = harness();
        Path dir = harness.sessionDir("../evil/../../id");
        assertTrue(dir.startsWith(stateRoot), dir.toString());
    }
}
