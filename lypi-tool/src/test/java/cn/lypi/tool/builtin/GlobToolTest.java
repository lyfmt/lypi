package cn.lypi.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
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

        ToolResult<String> result = tool.execute(Map.of("pattern", "src/**/*.txt", "maxResults", 2), context(), message -> {
        });

        assertFalse(result.isError());
        assertTrue(result.output().contains("src/a.txt"));
        assertTrue(result.output().contains("src/nested/b.txt"));
        assertFalse(result.output().contains("src/z.txt"));
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
