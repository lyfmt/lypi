package cn.lycode.ai.spec;

import cn.lycode.contracts.model.ModelSelection;
import cn.lycode.contracts.model.ThinkingLevel;
import java.util.List;
import java.util.Map;

public record LyCodeModelRequest(
    String requestId,
    ModelSelection model,
    ThinkingLevel thinkingLevel,
    String systemPrompt,
    List<LyCodeMessage> messages,
    List<LyCodeToolSpec> tools,
    LyCodeGenerationOptions options,
    Map<String, Object> metadata
) {
    public LyCodeModelRequest {
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
        metadata = Map.copyOf(metadata);
    }
}
