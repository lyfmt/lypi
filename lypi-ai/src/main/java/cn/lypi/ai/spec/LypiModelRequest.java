package cn.lypi.ai.spec;

import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import java.util.List;
import java.util.Map;

public record LypiModelRequest(
    String requestId,
    ModelSelection model,
    ThinkingLevel thinkingLevel,
    String systemPrompt,
    List<LypiMessage> messages,
    List<LypiToolSpec> tools,
    LypiGenerationOptions options,
    Map<String, Object> metadata
) {
    public LypiModelRequest {
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
        metadata = Map.copyOf(metadata);
    }
}
