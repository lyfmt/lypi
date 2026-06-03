package cn.lypi.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WriteToolTest {
    @TempDir
    Path tempDir;

    @Test
    void writesNewFileAndReturnsAuditSummary() throws Exception {
        WriteTool tool = new WriteTool();

        ToolResult<String> result = tool.execute(Map.of("path", "a.txt", "content", "hello"), context(), message -> {
        });

        assertFalse(result.isError());
        assertEquals("hello", Files.readString(tempDir.resolve("a.txt")));
        assertTrue(result.output().contains("path=a.txt"));
        assertTrue(result.output().contains("bytes=5"));
        assertTrue(result.output().contains("overwritten=false"));
    }

    @Test
    void parentDirectoriesRequireExplicitCreateParents() throws Exception {
        WriteTool tool = new WriteTool();

        ToolResult<String> failed = tool.execute(Map.of("path", "nested/a.txt", "content", "hello"), context(), message -> {
        });
        ToolResult<String> created = tool.execute(
            Map.of("path", "nested/a.txt", "content", "hello", "createParents", true),
            context(),
            message -> {
            }
        );

        assertTrue(failed.isError());
        assertFalse(created.isError());
        assertEquals("hello", Files.readString(tempDir.resolve("nested/a.txt")));
    }

    @Test
    void overwriteIsDestructiveAndPermissionAsk() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "old");
        WriteTool tool = new WriteTool();
        Map<String, Object> input = Map.of("path", "a.txt", "content", "new");

        assertTrue(tool.isDestructive(input));
        assertEquals(PermissionBehavior.ASK, tool.checkPermissions(input, context()).behavior());

        ToolResult<String> result = tool.execute(input, context(), message -> {
        });

        assertFalse(result.isError());
        assertEquals("new", Files.readString(tempDir.resolve("a.txt")));
        assertTrue(result.output().contains("overwritten=true"));
    }

    @Test
    void rejectsPathOutsideCwd() {
        WriteTool tool = new WriteTool();

        ToolResult<String> result = tool.execute(Map.of("path", "../a.txt", "content", "x"), context(), message -> {
        });

        assertTrue(result.isError());
        assertTrue(result.output().contains("越过当前工作目录"));
    }

    private ToolUseContext context() {
        return new ToolUseContext("ses_1", "msg_1", tempDir, Map.of("toolUseId", "toolu_1"));
    }
}
