package cn.lypi.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.common.ToolProgressKind;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GrepToolTest {
    @TempDir
    Path tempDir;

    @Test
    void searchesTextFilesWithStableOrderingAndMaxResults() throws Exception {
        Files.writeString(tempDir.resolve("b.txt"), "needle b\n");
        Files.writeString(tempDir.resolve("a.txt"), "needle a\nneedle again\n");
        GrepTool tool = new GrepTool();
        List<ToolProgress> progresses = new ArrayList<>();

        ToolResult<String> result = tool.execute(Map.of("pattern", "needle", "maxResults", 2), context(), progresses::add);

        assertFalse(result.isError());
        assertTrue(result.output().contains("a.txt:1:needle a"));
        assertTrue(result.output().contains("a.txt:2:needle again"));
        assertFalse(result.output().contains("b.txt:1:needle b"));
        assertTrue(progresses.stream().anyMatch(progress ->
            progress.kind() == ToolProgressKind.PHASE && "scanning".equals(progress.phase())));
        assertTrue(progresses.stream().anyMatch(progress ->
            progress.kind() == ToolProgressKind.COUNTER && "files".equals(progress.title()) && progress.current() == 2L));
        assertTrue(progresses.stream().anyMatch(progress ->
            progress.kind() == ToolProgressKind.STATUS && "matched".equals(progress.title())));
    }

    @Test
    void rejectsSearchPathOutsideCwd() {
        GrepTool tool = new GrepTool();

        ToolResult<String> result = tool.execute(Map.of("pattern", "needle", "path", "../"), context(), message -> {
        });

        assertTrue(result.isError());
        assertTrue(result.output().contains("越过当前工作目录"));
    }

    @Test
    void doesNotReadSymlinkFileOutsideWorkspace(@TempDir Path outsideDir) throws Exception {
        Files.writeString(outsideDir.resolve("secret.txt"), "needle secret\n");
        Files.createSymbolicLink(tempDir.resolve("secret-link.txt"), outsideDir.resolve("secret.txt"));
        GrepTool tool = new GrepTool();

        ToolResult<String> result = tool.execute(Map.of("pattern", "needle"), context(), message -> {
        });

        assertFalse(result.isError());
        assertFalse(result.output().contains("secret"));
    }

    @Test
    void ignoresSessionJsonlFilesByDefault() throws Exception {
        Files.createDirectories(tempDir.resolve(".lypi/sessions"));
        Files.writeString(tempDir.resolve(".lypi/sessions/session_1.jsonl"), "needle tool_call\n");
        Files.writeString(tempDir.resolve("source.txt"), "needle source\n");
        GrepTool tool = new GrepTool();

        ToolResult<String> result = tool.execute(Map.of("pattern", "needle"), context(), message -> {
        });

        assertFalse(result.isError());
        assertTrue(result.output().contains("source.txt:1:needle source"));
        assertFalse(result.output().contains(".lypi/sessions/session_1.jsonl"));
    }

    @Test
    void isReadOnlyAndConcurrencySafe() {
        GrepTool tool = new GrepTool();

        assertTrue(tool.isReadOnly(Map.of()));
        assertTrue(tool.isConcurrencySafe(Map.of()));
        assertFalse(tool.isDestructive(Map.of()));
    }

    private ToolUseContext context() {
        return new ToolUseContext("ses_1", "msg_1", tempDir, Map.of("toolUseId", "toolu_1"));
    }
}
