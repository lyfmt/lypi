package cn.lypi.tool;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.tool.ToolExecutionStatus;
import cn.lypi.contracts.tool.ToolResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 创建工具运行时内部结果。
 */
final class ToolResultFactory {
    ToolResult<String> error(String toolUseId, String message) {
        return error(toolUseId, message, null);
    }

    ToolResult<String> error(String toolUseId, String message, ToolExecutionStatus status) {
        String safeMessage = message == null || message.isBlank() ? "工具调用失败。" : message;
        Map<String, Object> metadata = status == null ? Map.of() : Map.of("status", status.name());
        AgentMessage agentMessage = new AgentMessage(
            "msg_" + toolUseId,
            MessageRole.TOOL_RESULT,
            MessageKind.TOOL_RESULT,
            List.of(new ToolResultContentBlock(toolUseId, safeMessage, true, metadata)),
            Instant.now(),
            Optional.empty(),
            Optional.empty()
        );
        return new ToolResult<>(safeMessage, true, List.of(agentMessage), Optional.empty());
    }
}
