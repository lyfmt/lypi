package cn.lypi.agent;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ToolCallMapper {
    public List<ToolUseRequest> requestsFrom(AgentMessage assistant) {
        List<ToolUseRequest> requests = new ArrayList<>();
        for (cn.lypi.contracts.context.ContentBlock block : assistant.content()) {
            if (block instanceof ToolCallContentBlock toolCall) {
                requests.add(new ToolUseRequest(
                    toolCall.toolUseId(),
                    toolCall.toolName(),
                    inputFrom(toolCall),
                    assistant.id()
                ));
            }
        }
        return List.copyOf(requests);
    }

    private Map<String, Object> inputFrom(ToolCallContentBlock toolCall) {
        Object input = toolCall.metadata().get("input");
        if (input instanceof Map<?, ?> map) {
            java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();
            map.forEach((key, value) -> values.put(String.valueOf(key), value));
            return Map.copyOf(values);
        }
        return Map.of();
    }
}
