package cn.lypi.agent;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.ToolResultContentBlock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ToolResultMessageMarker {
    private ToolResultMessageMarker() {
    }

    static AgentMessage markPendingToolOutput(AgentMessage message) {
        if (message.role() != MessageRole.TOOL_RESULT) {
            return message;
        }
        List<ContentBlock> content = message.content().stream()
            .map(ToolResultMessageMarker::markPendingToolOutput)
            .toList();
        return new AgentMessage(
            message.id(),
            message.role(),
            message.kind(),
            content,
            message.timestamp(),
            message.usage(),
            message.stopReason()
        );
    }

    private static ContentBlock markPendingToolOutput(ContentBlock block) {
        if (!(block instanceof ToolResultContentBlock toolResult)) {
            return block;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(toolResult.metadata());
        metadata.put("openaiPendingToolOutput", true);
        return new ToolResultContentBlock(
            toolResult.toolUseId(),
            toolResult.text(),
            toolResult.error(),
            Map.copyOf(metadata)
        );
    }
}
