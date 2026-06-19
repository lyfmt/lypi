package cn.lypi.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.common.ToolProgressKind;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GlobToolTest {
    @TempDir
    Path tempDir;

    @Test
    void matchesFilesWithStableOrderingAndMaxResults() throws Exception {
        Files.createDirectories(tempDir.resolve("src/nested"));
        Files.writeString(tempDir.resolve("src/z.txt"), "z");
        Files.writeString(tempDir.resolve("src/a.txt"), "a");
        Files.writeString(tempDir.resolve("src/nested/b.txt"), "b");
        GlobTool tool = new GlobTool();
        List<ToolProgress> progresses = new ArrayList<>();

        ToolResult<String> result = tool.execute(Map.of("pattern", "src/**/*.txt", "maxResults", 2), context(), progresses::add);

        assertFalse(result.isError());
        assertTrue(result.output().contains("src/a.txt"));
        assertTrue(result.output().contains("src/nested/b.txt"));
        assertFalse(result.output().contains("src/z.txt"));
        assertTrue(progresses.stream().anyMatch(progress ->
            progress.kind() == ToolProgressKind.PHASE && "scanning".equals(progress.phase())));
        assertTrue(progresses.stream().anyMatch(progress ->
            progress.kind() == ToolProgressKind.COUNTER && "files".equals(progress.title()) && progress.current() == 3L));
        assertTrue(progresses.stream().anyMatch(progress ->
            progress.kind() == ToolProgressKind.STATUS && "matched".equals(progress.title()) && progress.current() == 2L));
    }

    @Test
    void rejectsBasePathOutsideCwd() {
        GlobTool tool = new GlobTool();

        ToolResult<String> result = tool.execute(Map.of("pattern", "**/*.txt", "path", "../"), context(), message -> {
        });

        assertTrue(result.isError());
        assertTrue(result.output().contains("越过当前工作目录"));
    }

    @Test
    void doesNotReportSymlinkFileOutsideWorkspace(@TempDir Path outsideDir) throws Exception {
        Files.writeString(outsideDir.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(tempDir.resolve("secret-link.txt"), outsideDir.resolve("secret.txt"));
        GlobTool tool = new GlobTool();

        ToolResult<String> result = tool.execute(Map.of("pattern", "*.txt"), context(), message -> {
        });

        assertFalse(result.isError());
        assertFalse(result.output().contains("secret-link.txt"));
    }

    @Test
    void ignoresSessionJsonlFilesByDefault() throws Exception {
        Files.createDirectories(tempDir.resolve(".lypi/sessions"));
        Files.writeString(tempDir.resolve(".lypi/sessions/session_1.jsonl"), "session");
        Files.writeString(tempDir.resolve("source.txt"), "source");
        GlobTool tool = new GlobTool();

        ToolResult<String> result = tool.execute(Map.of("pattern", "**/*"), context(), message -> {
        });

        assertFalse(result.isError());
        assertTrue(result.output().contains("source.txt"));
        assertFalse(result.output().contains(".lypi/sessions/session_1.jsonl"));
    }

    @Test
    void ignoresGitMetadataByDefault() throws Exception {
        Files.createDirectories(tempDir.resolve(".git/hooks"));
        Files.writeString(tempDir.resolve(".git/config"), "config");
        Files.writeString(tempDir.resolve(".git/hooks/pre-commit.sample"), "hook");
        Files.writeString(tempDir.resolve("source.txt"), "source");
        GlobTool tool = new GlobTool();

        ToolResult<String> result = tool.execute(Map.of("pattern", "**/*"), context(), message -> {
        });

        assertFalse(result.isError());
        assertTrue(result.output().contains("source.txt"));
        assertFalse(result.output().contains(".git/config"));
        assertFalse(result.output().contains(".git/hooks/pre-commit.sample"));
    }

    @Test
    void returnsQuicklyWhenScanningSmallWorktreeWithGitMetadata() throws Exception {
        Files.createDirectories(tempDir.resolve(".worktrees/weather-card/.git/hooks"));
        Files.writeString(tempDir.resolve(".worktrees/weather-card/.git/config"), "config");
        Files.writeString(tempDir.resolve(".worktrees/weather-card/.git/hooks/pre-commit.sample"), "hook");
        Files.writeString(tempDir.resolve(".worktrees/weather-card/index.html"), "<html></html>");
        Files.writeString(tempDir.resolve(".worktrees/weather-card/main.js"), "console.log('weather');");
        Files.writeString(tempDir.resolve(".gitignore"), ".worktrees/");
        GlobTool tool = new GlobTool();

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            ToolResult<String> first = tool.execute(Map.of(
                "pattern", "*",
                "path", tempDir.resolve(".worktrees/weather-card").toString(),
                "maxResults", 200
            ), context(), message -> {
            });
            ToolResult<String> second = tool.execute(Map.of(
                "pattern", "**/*",
                "path", tempDir.resolve(".worktrees/weather-card").toString(),
                "maxResults", 200
            ), context(), message -> {
            });
            ToolResult<String> third = tool.execute(Map.of(
                "pattern", ".gitignore",
                "path", tempDir.toString(),
                "maxResults", 50
            ), context(), message -> {
            });

            assertFalse(first.isError());
            assertFalse(second.isError());
            assertFalse(third.isError());
            assertTrue(first.output().contains(".worktrees/weather-card/index.html"));
            assertTrue(second.output().contains(".worktrees/weather-card/main.js"));
            assertTrue(third.output().contains(".gitignore"));
            assertFalse(second.output().contains(".git/config"));
        });
    }

    @Test
    void isReadOnlyAndConcurrencySafe() {
        GlobTool tool = new GlobTool();

        assertTrue(tool.isReadOnly(Map.of()));
        assertTrue(tool.isConcurrencySafe(Map.of()));
        assertFalse(tool.isDestructive(Map.of()));
    }

    private ToolUseContext context() {
        return new ToolUseContext("ses_1", "msg_1", tempDir, Map.of("toolUseId", "toolu_1"));
    }
}
