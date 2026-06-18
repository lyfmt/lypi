package cn.lypi.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.common.ToolProgressKind;
import cn.lypi.contracts.context.AttachmentContentBlock;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadToolTest {
    @TempDir
    Path tempDir;

    @Test
    void readsUtf8FileWithLineNumbers() throws Exception {
        Path file = tempDir.resolve("notes.txt");
        Files.writeString(file, "alpha\nbeta\n");
        ReadTool tool = new ReadTool();
        List<ToolProgress> progresses = new ArrayList<>();

        ToolResult<String> result = tool.execute(Map.of("path", "notes.txt"), context(), progresses::add);

        assertFalse(result.isError());
        assertTrue(result.output().contains("1 | alpha"));
        assertTrue(result.output().contains("2 | beta"));
        ToolResultContentBlock block = (ToolResultContentBlock) result.newMessages().getFirst().content().getFirst();
        assertEquals(result.output(), block.text());
        assertTrue(progresses.stream().anyMatch(progress ->
            progress.kind() == ToolProgressKind.PHASE && "reading".equals(progress.phase())));
        assertTrue(progresses.stream().anyMatch(progress ->
            progress.kind() == ToolProgressKind.STATUS && "read lines".equals(progress.title()) && progress.current() == 2L));
    }

    @Test
    void supportsOffsetAndLimit() throws Exception {
        Files.writeString(tempDir.resolve("notes.txt"), "one\ntwo\nthree\nfour\n");
        ReadTool tool = new ReadTool();

        ToolResult<String> result = tool.execute(Map.of("path", "notes.txt", "offset", 2, "limit", 2), context(), message -> {
        });

        assertFalse(result.isError());
        assertFalse(result.output().contains("1 | one"));
        assertTrue(result.output().contains("2 | two"));
        assertTrue(result.output().contains("3 | three"));
        assertFalse(result.output().contains("4 | four"));
    }

    @Test
    void readsSingleLineFileWithoutTrailingNewline() throws Exception {
        Files.writeString(tempDir.resolve("notes.txt"), "alpha");

        ToolResult<String> result = new ReadTool().execute(Map.of("path", "notes.txt"), context(), message -> {
        });

        assertFalse(result.isError());
        assertTrue(result.output().contains("1 | alpha"));
    }

    @Test
    void returnsToolErrorForMissingFileAndDirectory() throws Exception {
        Files.createDirectory(tempDir.resolve("dir"));
        ReadTool tool = new ReadTool();

        assertTrue(tool.execute(Map.of("path", "missing.txt"), context(), message -> {
        }).isError());
        assertTrue(tool.execute(Map.of("path", "dir"), context(), message -> {
        }).isError());
    }

    @Test
    void readsImageAsAttachmentWithoutPrintingPathOrBase64() throws Exception {
        Files.write(tempDir.resolve("image.png"), png1x1());
        ReadTool tool = new ReadTool();

        ToolResult<String> result = tool.execute(Map.of("path", "image.png"), context(), progress -> {
        });

        assertFalse(result.isError());
        assertTrue(result.output().contains("Read image file [image/png]"));
        assertFalse(result.output().contains("image.png"));
        assertFalse(result.output().contains("base64"));
        assertEquals(2, result.newMessages().getFirst().content().size());
        assertTrue(result.newMessages().getFirst().content().get(0) instanceof ToolResultContentBlock);
        assertTrue(result.newMessages().getFirst().content().get(1) instanceof AttachmentContentBlock);
    }

    @Test
    void rejectsUnsupportedBinaryFiles() throws Exception {
        Files.write(tempDir.resolve("archive.zip"), new byte[] {'P', 'K', 0x03, 0x04});

        ToolResult<String> result = new ReadTool().execute(Map.of("path", "archive.zip"), context(), progress -> {
        });

        assertTrue(result.isError());
        assertTrue(result.output().contains("不能读取二进制文件"));
    }

    @Test
    void exposesReadOnlyConcurrencySafeMetadata() {
        ReadTool tool = new ReadTool();

        assertTrue(tool.isReadOnly(Map.of("path", "notes.txt")));
        assertTrue(tool.isConcurrencySafe(Map.of("path", "notes.txt")));
        assertFalse(tool.isDestructive(Map.of("path", "notes.txt")));
        assertEquals(PermissionBehavior.ALLOW, tool.checkPermissions(Map.of("path", "notes.txt"), context()).behavior());
    }

    private ToolUseContext context() {
        return new ToolUseContext("ses_1", "msg_1", tempDir, Map.of("toolUseId", "toolu_1"));
    }

    private static byte[] png1x1() {
        return Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=");
    }
}
