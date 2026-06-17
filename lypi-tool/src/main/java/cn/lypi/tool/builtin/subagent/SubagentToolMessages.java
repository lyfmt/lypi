package cn.lypi.tool.builtin.subagent;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

final class SubagentToolMessages {
    private SubagentToolMessages() {
    }

    static AgentMessage serializeForContext(String output) {
        return toolMessage("toolu_unknown", output, false);
    }

    static ToolResult<String> success(ToolUseContext context, String text) {
        return new ToolResult<>(text, false, List.of(toolMessage(toolUseId(context), text, false)), Optional.empty());
    }

    static ToolResult<String> error(ToolUseContext context, String message) {
        String text = message == null || message.isBlank() ? "工具调用失败。" : message;
        return new ToolResult<>(text, true, List.of(toolMessage(toolUseId(context), text, true)), Optional.empty());
    }

    static String toolUseId(ToolUseContext context) {
        Object value = context.metadata().get("toolUseId");
        return value == null ? "toolu_unknown" : value.toString();
    }

    static AgentMessage toolMessage(String toolUseId, String text, boolean error) {
        return new AgentMessage(
            "msg_" + toolUseId,
            MessageRole.TOOL_RESULT,
            MessageKind.TOOL_RESULT,
            List.of(new ToolResultContentBlock(toolUseId, text, error)),
            Instant.now(),
            Optional.empty(),
            Optional.empty()
        );
    }
}
