package cn.lypi.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.tool.ToolUseContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspacePathsTest {
    @TempDir
    Path tempDir;

    @Test
    void resolvesMissingPathRelativeToWorkspaceAndRejectsTraversal() {
        ToolUseContext context = context();

        assertEquals(tempDir.resolve("notes.txt"), WorkspacePaths.resolvePath(Map.of("path", "notes.txt"), context, "path"));

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> WorkspacePaths.resolvePath(Map.of("path", "../outside.txt"), context, "path")
        );
        assertTrue(exception.getMessage().contains("路径越过当前工作目录"));
    }

    @Test
    void rendersRelativePathOnlyForWorkspaceChildren() {
        ToolUseContext context = context();

        assertEquals("nested/file.txt", WorkspacePaths.relativePath(tempDir.resolve("nested/file.txt"), context));
        assertEquals(".", WorkspacePaths.relativePath(tempDir, context));
        assertTrue(WorkspacePaths.relativePath(tempDir.getParent(), context).endsWith(tempDir.getParent().toString()));
    }

    @Test
    void realPathChecksRejectSymlinkEscapes() throws Exception {
        Path outside = Files.createTempDirectory("lypi-outside");
        Path link = tempDir.resolve("escape");
        try {
            Files.createSymbolicLink(link, outside);

            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> WorkspacePaths.requireRealPathInsideWorkspace(link, context())
            );

            assertTrue(exception.getMessage().contains("路径经符号链接越过当前工作目录"));
            assertFalse(WorkspacePaths.realPathInsideWorkspace(link, context()));
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void writesAtomicallyAndCleansTempFileOnFailure() throws Exception {
        Path file = tempDir.resolve("a.txt");

        WorkspacePaths.writeAtomically(file, "hello");

        assertEquals("hello", Files.readString(file));
    }

    private ToolUseContext context() {
        return new ToolUseContext("ses_1", "msg_1", tempDir, Map.of());
    }
}
