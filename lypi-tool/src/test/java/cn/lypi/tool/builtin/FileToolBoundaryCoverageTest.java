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

class FileToolBoundaryCoverageTest {
    @TempDir
    Path tempDir;

    @Test
    void readRejectsSymlinkEscapeWithExplicitError() throws Exception {
        Path workspace = workspace();
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("secret.txt"), "TOP-SECRET-CONTENT");
        Files.createSymbolicLink(workspace.resolve("secret-link.txt"), outside.resolve("secret.txt"));

        ToolResult<String> result = new ReadTool().execute(
            Map.of("path", "secret-link.txt"),
            context(workspace),
            progress -> {
            }
        );

        assertTrue(result.isError());
        assertTrue(result.output().contains("符号链接越过当前工作目录"));
        assertFalse(result.output().contains("TOP-SECRET-CONTENT"));
    }

    @Test
    void writeRejectsSymlinkEscapeBeforeReplacingLinkOrOutsideTarget() throws Exception {
        Path workspace = workspace();
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(outside);
        Path outsideFile = outside.resolve("secret.txt");
        Files.writeString(outsideFile, "outside");
        Path link = workspace.resolve("secret-link.txt");
        Files.createSymbolicLink(link, outsideFile);
        WriteTool tool = new WriteTool();
        Map<String, Object> input = Map.of("path", "secret-link.txt", "content", "inside");

        ToolResult<String> result = tool.execute(input, context(workspace), progress -> {
        });

        assertTrue(result.isError());
        assertTrue(result.output().contains("符号链接越过当前工作目录"));
        assertEquals(PermissionBehavior.DENY, tool.checkPermissions(input, context(workspace)).behavior());
        assertTrue(Files.isSymbolicLink(link));
        assertEquals("outside", Files.readString(outsideFile));
    }

    @Test
    void writeRejectsDanglingSymlinkEscapeBeforeReplacingLink() throws Exception {
        Path workspace = workspace();
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(outside);
        Path link = workspace.resolve("new-link.txt");
        Files.createSymbolicLink(link, outside.resolve("new.txt"));
        WriteTool tool = new WriteTool();
        Map<String, Object> input = Map.of("path", "new-link.txt", "content", "inside");

        ToolResult<String> result = tool.execute(input, context(workspace), progress -> {
        });

        assertTrue(result.isError());
        assertTrue(result.output().contains("符号链接越过当前工作目录"));
        assertEquals(PermissionBehavior.DENY, tool.checkPermissions(input, context(workspace)).behavior());
        assertTrue(Files.isSymbolicLink(link));
        assertFalse(Files.exists(outside.resolve("new.txt")));
    }

    @Test
    void writeRejectsSymlinkParentEscapeBeforeCreatingOutsideDirectories() throws Exception {
        Path workspace = workspace();
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(outside);
        Files.createSymbolicLink(workspace.resolve("outside-link"), outside);

        ToolResult<String> result = new WriteTool().execute(
            Map.of("path", "outside-link/new-dir/file.txt", "content", "inside", "createParents", true),
            context(workspace),
            progress -> {
            }
        );

        assertTrue(result.isError());
        assertTrue(result.output().contains("符号链接越过当前工作目录"));
        assertFalse(Files.exists(outside.resolve("new-dir")));
    }

    @Test
    void editRejectsSymlinkEscapeBeforeReadingOutsideFile() throws Exception {
        Path workspace = workspace();
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(outside);
        Path outsideFile = outside.resolve("secret.txt");
        Files.writeString(outsideFile, "old");
        Files.createSymbolicLink(workspace.resolve("secret-link.txt"), outsideFile);

        ToolResult<String> result = new EditTool().execute(
            Map.of("path", "secret-link.txt", "oldString", "old", "newString", "new"),
            context(workspace),
            progress -> {
            }
        );

        assertTrue(result.isError());
        assertTrue(result.output().contains("符号链接越过当前工作目录"));
        assertEquals("old", Files.readString(outsideFile));
    }

    @Test
    void readWriteAndEditReportDirectoryPathErrorsExplicitly() throws Exception {
        Path workspace = workspace();
        Files.createDirectories(workspace.resolve("dir"));

        ToolResult<String> read = new ReadTool().execute(Map.of("path", "dir"), context(workspace), progress -> {
        });
        ToolResult<String> write = new WriteTool().execute(
            Map.of("path", "dir", "content", "text"),
            context(workspace),
            progress -> {
            }
        );
        ToolResult<String> edit = new EditTool().execute(
            Map.of("path", "dir", "oldString", "old", "newString", "new"),
            context(workspace),
            progress -> {
            }
        );

        assertTrue(read.isError());
        assertTrue(read.output().contains("不能读取目录"));
        assertTrue(write.isError());
        assertTrue(write.output().contains("不能写入目录"));
        assertTrue(edit.isError());
        assertTrue(edit.output().contains("不能编辑目录"));
    }

    @Test
    void editRejectsDuplicateReplacementTextWithExplicitError() throws Exception {
        Path workspace = workspace();
        Files.writeString(workspace.resolve("notes.txt"), "target\nmiddle\ntarget\n");

        ToolResult<String> result = new EditTool().execute(
            Map.of("path", "notes.txt", "oldString", "target", "newString", "replacement"),
            context(workspace),
            progress -> {
            }
        );

        assertTrue(result.isError());
        assertTrue(result.output().contains("oldString 在文件中出现多次"));
        assertEquals("target\nmiddle\ntarget\n", Files.readString(workspace.resolve("notes.txt")));
    }

    private Path workspace() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);
        return workspace;
    }

    private ToolUseContext context(Path cwd) {
        return new ToolUseContext("ses_1", "msg_1", cwd, Map.of("toolUseId", "toolu_1"));
    }
}
