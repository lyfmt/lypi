package cn.lypi.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.common.ToolProgressKind;
import cn.lypi.contracts.security.AdditionalPermissionProfile;
import cn.lypi.contracts.security.FileSystemAccessMode;
import cn.lypi.contracts.security.FileSystemPath;
import cn.lypi.contracts.security.FileSystemPermissionEntry;
import cn.lypi.contracts.security.FileSystemPermissionPolicy;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WriteToolTest {
    @TempDir
    Path tempDir;

    @Test
    void writesNewFileAndReturnsAuditSummary() throws Exception {
        WriteTool tool = new WriteTool();
        List<ToolProgress> progresses = new ArrayList<>();

        ToolResult<String> result = tool.execute(Map.of("path", "a.txt", "content", "hello"), context(), progresses::add);

        assertFalse(result.isError());
        assertEquals("hello", Files.readString(tempDir.resolve("a.txt")));
        assertTrue(result.output().contains("path=a.txt"));
        assertTrue(result.output().contains("bytes=5"));
        assertTrue(result.output().contains("overwritten=false"));
        assertTrue(progresses.stream().anyMatch(progress ->
            progress.kind() == ToolProgressKind.PHASE && "writing".equals(progress.phase())));
        assertTrue(progresses.stream().anyMatch(progress ->
            progress.kind() == ToolProgressKind.STATUS && "written bytes".equals(progress.title()) && progress.current() == 5L));
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
        assertEquals(
            PermissionBehavior.DENY,
            tool.checkPermissions(Map.of("path", "../a.txt", "content", "x"), context()).behavior()
        );
    }

    @Test
    void writesOutsideCwdWhenAdditionalPermissionAllowsWrite(@TempDir Path outsideDir) throws Exception {
        WriteTool tool = new WriteTool();
        Path outsideFile = outsideDir.resolve("a.txt");

        ToolResult<String> result = tool.execute(
            Map.of("path", outsideFile.toString(), "content", "approved"),
            context(additionalFileSystem(outsideDir, FileSystemAccessMode.WRITE)),
            message -> {
            }
        );

        assertFalse(result.isError());
        assertEquals("approved", Files.readString(outsideFile));
        assertTrue(result.output().contains(outsideFile.toString()));
    }

    @Test
    void readOnlyAdditionalPermissionDoesNotAllowOutsideWrite(@TempDir Path outsideDir) {
        WriteTool tool = new WriteTool();
        Path outsideFile = outsideDir.resolve("a.txt");

        ToolResult<String> result = tool.execute(
            Map.of("path", outsideFile.toString(), "content", "blocked"),
            context(additionalFileSystem(outsideDir, FileSystemAccessMode.READ)),
            message -> {
            }
        );

        assertTrue(result.isError());
        assertTrue(result.output().contains("越过当前工作目录"));
    }

    private ToolUseContext context() {
        return new ToolUseContext("ses_1", "msg_1", tempDir, Map.of("toolUseId", "toolu_1"));
    }

    private ToolUseContext context(AdditionalPermissionProfile additionalPermissions) {
        return new ToolUseContext(
            "ses_1",
            "msg_1",
            tempDir,
            Map.of(
                "toolUseId", "toolu_1",
                "additionalPermissions", additionalPermissions,
                "approvedAdditionalPermissions", true
            )
        );
    }

    private AdditionalPermissionProfile additionalFileSystem(Path path, FileSystemAccessMode accessMode) {
        return new AdditionalPermissionProfile(
            Optional.of(FileSystemPermissionPolicy.restricted(List.of(
                new FileSystemPermissionEntry(FileSystemPath.exactPath(path.toString()), accessMode)
            ))),
            Optional.empty()
        );
    }
}
