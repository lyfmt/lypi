package cn.lypi.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.AttachmentContentBlock;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.util.List;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolMessagesTest {
    @Test
    void createsSuccessAndErrorResultsWithToolResultMessages() {
        ToolResult<String> success = ToolMessages.success("toolu_1", "ok");
        ToolResult<String> error = ToolMessages.error("toolu_2", "");

        assertFalse(success.isError());
        assertEquals("ok", success.output());
        assertBlock(success, "toolu_1", "ok", false);

        assertTrue(error.isError());
        assertEquals("工具调用失败。", error.output());
        assertBlock(error, "toolu_2", "工具调用失败。", true);
    }

    @Test
    void extractsToolUseIdAndSerializesOutputForContext() {
        ToolUseContext context = new ToolUseContext("ses_1", "msg_1", Path.of("."), Map.of("toolUseId", "toolu_9"));

        assertEquals("toolu_9", ToolMessages.toolUseId(context));

        AgentMessage message = ToolMessages.serializeForContext("hello");

        assertEquals("msg_toolu_unknown", message.id());
        assertEquals(MessageRole.TOOL_RESULT, message.role());
        assertEquals(MessageKind.TOOL_RESULT, message.kind());
        assertBlock(message, "toolu_unknown", "hello", false);
    }

    @Test
    void toolUseIdDefaultsWhenMetadataIsMissing() {
        ToolUseContext context = new ToolUseContext("ses_1", "msg_1", Path.of("."), Map.of());

        assertEquals("toolu_unknown", ToolMessages.toolUseId(context));
    }

    @Test
    void createsToolResultWithAdditionalContentBlocks() {
        AttachmentContentBlock attachment = new AttachmentContentBlock(
            "att-1",
            "Image: image/png",
            "image/png",
            Map.of("imageUrl", "data:image/png;base64,AAA", "toolUseId", "toolu_1")
        );

        ToolResult<String> result = ToolMessages.success(
            "toolu_1",
            "Read image file [image/png]",
            List.of(attachment)
        );

        assertFalse(result.isError());
        assertEquals("Read image file [image/png]", result.output());
        assertEquals(2, result.newMessages().getFirst().content().size());
        assertBlock(result, "toolu_1", "Read image file [image/png]", false);
        assertEquals(attachment, result.newMessages().getFirst().content().get(1));
    }

    private static void assertBlock(ToolResult<String> result, String toolUseId, String text, boolean error) {
        assertBlock(result.newMessages().getFirst(), toolUseId, text, error);
    }

    private static void assertBlock(AgentMessage message, String toolUseId, String text, boolean error) {
        ToolResultContentBlock block = (ToolResultContentBlock) message.content().getFirst();
        assertEquals(toolUseId, block.toolUseId());
        assertEquals(text, block.text());
        assertEquals(error, block.error());
    }
}
