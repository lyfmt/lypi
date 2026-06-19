package cn.lypi.tool.builtin.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SubagentToolMessagesTest {
    @Test
    void createsSuccessAndErrorResultsUsingContextToolUseId() {
        ToolUseContext context = new ToolUseContext("ses_1", "msg_1", Path.of("."), Map.of("toolUseId", "toolu_agent"));

        ToolResult<String> success = SubagentToolMessages.success(context, "ok");
        ToolResult<String> error = SubagentToolMessages.error(context, "");

        assertFalse(success.isError());
        assertBlock(success, "toolu_agent", "ok", false);
        assertTrue(error.isError());
        assertEquals("工具调用失败。", error.output());
        assertBlock(error, "toolu_agent", "工具调用失败。", true);
    }

    @Test
    void serializesForContextWithUnknownToolUseIdWhenNoRuntimeContextExists() {
        AgentMessage message = SubagentToolMessages.serializeForContext("hello");

        assertEquals("msg_toolu_unknown", message.id());
        assertEquals(MessageRole.TOOL_RESULT, message.role());
        assertEquals(MessageKind.TOOL_RESULT, message.kind());
        assertBlock(message, "toolu_unknown", "hello", false);
    }

    @Test
    void toolUseIdDefaultsWhenMetadataIsMissing() {
        ToolUseContext context = new ToolUseContext("ses_1", "msg_1", Path.of("."), Map.of());

        assertEquals("toolu_unknown", SubagentToolMessages.toolUseId(context));
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
