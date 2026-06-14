package cn.lypi.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.common.ToolProgressKind;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditToolTest {
    @TempDir
    Path tempDir;

    @Test
    void replacesUniqueOldStringAndReturnsDiffPreview() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "alpha\nold\nomega\n");
        EditTool tool = new EditTool();
        List<ToolProgress> progresses = new ArrayList<>();

        ToolResult<String> result = tool.execute(
            Map.of("path", "a.txt", "oldString", "old", "newString", "new"),
            context(),
            progresses::add
        );

        assertFalse(result.isError());
        assertEquals("alpha\nnew\nomega\n", Files.readString(tempDir.resolve("a.txt")));
        assertTrue(result.output().contains("- old"));
        assertTrue(result.output().contains("+ new"));
        assertTrue(progresses.stream().anyMatch(progress ->
            progress.kind() == ToolProgressKind.PHASE && "editing".equals(progress.phase())));
        assertTrue(progresses.stream().anyMatch(progress ->
            progress.kind() == ToolProgressKind.STATUS && "changed".equals(progress.title())));
    }

    @Test
    void failsWhenOldStringMissingOrNotUniqueOrUnchanged() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "one\ntwo\ntwo\n");
        EditTool tool = new EditTool();

        assertTrue(tool.execute(Map.of("path", "a.txt", "oldString", "missing", "newString", "x"), context(), message -> {
        }).isError());
        assertTrue(tool.execute(Map.of("path", "a.txt", "oldString", "two", "newString", "x"), context(), message -> {
        }).isError());
        assertTrue(tool.execute(Map.of("path", "a.txt", "oldString", "one", "newString", "one"), context(), message -> {
        }).isError());
    }

    @Test
    void editIsDestructiveAndAllowsByDefaultAtToolLevel() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "old");
        EditTool tool = new EditTool();
        Map<String, Object> input = Map.of("path", "a.txt", "oldString", "old", "newString", "new");

        assertTrue(tool.isDestructive(input));
        assertEquals(PermissionBehavior.ALLOW, tool.checkPermissions(input, context()).behavior());
        assertFalse(tool.isReadOnly(input));
        assertFalse(tool.isConcurrencySafe(input));
    }

    private ToolUseContext context() {
        return new ToolUseContext("ses_1", "msg_1", tempDir, Map.of("toolUseId", "toolu_1"));
    }
}
