package cn.lycode.tool.builtin;

import cn.lycode.contracts.context.AgentMessage;
import cn.lycode.contracts.context.ContentBlock;
import cn.lycode.contracts.context.MessageKind;
import cn.lycode.contracts.context.MessageRole;
import cn.lycode.contracts.context.ToolResultContentBlock;
import cn.lycode.contracts.tool.ToolResult;
import cn.lycode.contracts.tool.ToolUseContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

final class ToolMessages {
    private ToolMessages() {
    }

    static AgentMessage serializeForContext(String output) {
        return toolMessage("toolu_unknown", output, false);
    }

    static ToolResult<String> success(String toolUseId, String text) {
        return new ToolResult<>(text, false, List.of(toolMessage(toolUseId, text, false)), Optional.empty());
    }

    static ToolResult<String> success(String toolUseId, String text, List<ContentBlock> additionalBlocks) {
        return new ToolResult<>(
            text,
            false,
            List.of(toolMessage(toolUseId, text, false, additionalBlocks)),
            Optional.empty()
        );
    }

    static ToolResult<String> error(String toolUseId, String message) {
        String text = message == null || message.isBlank() ? "工具调用失败。" : message;
        return new ToolResult<>(text, true, List.of(toolMessage(toolUseId, text, true)), Optional.empty());
    }

    static String toolUseId(ToolUseContext context) {
        Object value = context.metadata().get("toolUseId");
        return value == null ? "toolu_unknown" : value.toString();
    }

    static AgentMessage toolMessage(String toolUseId, String text, boolean error) {
        return toolMessage(toolUseId, text, error, List.of());
    }

    private static AgentMessage toolMessage(
        String toolUseId,
        String text,
        boolean error,
        List<ContentBlock> additionalBlocks
    ) {
        List<ContentBlock> content = new java.util.ArrayList<>();
        content.add(new ToolResultContentBlock(toolUseId, text, error));
        content.addAll(additionalBlocks);
        return new AgentMessage(
            "msg_" + toolUseId,
            MessageRole.TOOL_RESULT,
            MessageKind.TOOL_RESULT,
            List.copyOf(content),
            Instant.now(),
            Optional.empty(),
            Optional.empty()
        );
    }
}
