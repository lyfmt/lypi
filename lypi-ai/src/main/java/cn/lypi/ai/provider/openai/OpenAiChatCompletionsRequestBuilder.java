package cn.lypi.ai.provider.openai;

import cn.lypi.ai.OpenAiCompatibleThinkingParameterMapper;
import cn.lypi.ai.spec.LypiAttachmentBlock;
import cn.lypi.ai.spec.LypiContentBlock;
import cn.lypi.ai.spec.LypiErrorBlock;
import cn.lypi.ai.spec.LypiMessage;
import cn.lypi.ai.spec.LypiModelRequest;
import cn.lypi.ai.spec.LypiRole;
import cn.lypi.ai.spec.LypiTextBlock;
import cn.lypi.ai.spec.LypiThinkingBlock;
import cn.lypi.ai.spec.LypiToolCallBlock;
import cn.lypi.ai.spec.LypiToolResultBlock;
import cn.lypi.ai.spec.LypiToolSpec;
import cn.lypi.contracts.model.ThinkingLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.Objects;

public final class OpenAiChatCompletionsRequestBuilder {
    private final ObjectMapper objectMapper;

    public OpenAiChatCompletionsRequestBuilder() {
        this(new ObjectMapper());
    }

    public OpenAiChatCompletionsRequestBuilder(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * 构造 OpenAI Chat Completions 请求体。
     *
     * 该请求体仅用于 Responses 不可用时的兼容回退。
     */
    public ObjectNode build(LypiModelRequest request, OpenAiProviderConfig config) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(config, "config");
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", request.model().modelId());
        body.put("stream", true);
        request.options().maxOutputTokens().ifPresent(tokens -> body.put("max_tokens", tokens));
        request.options().temperature().ifPresent(temperature -> body.put("temperature", temperature));
        body.set("messages", messages(request));
        if (!request.tools().isEmpty()) {
            body.set("tools", tools(request));
        }
        reasoning(request.thinkingLevel()).ifPresent(effort -> body.put("reasoning_effort", effort));
        return body;
    }

    private ArrayNode messages(LypiModelRequest request) {
        ArrayNode messages = objectMapper.createArrayNode();
        if (!request.systemPrompt().isBlank()) {
            ObjectNode system = objectMapper.createObjectNode();
            system.put("role", "system");
            system.put("content", request.systemPrompt());
            messages.add(system);
        }
        for (LypiMessage message : request.messages()) {
            appendMessage(messages, message);
        }
        return messages;
    }

    private void appendMessage(ArrayNode messages, LypiMessage message) {
        for (LypiContentBlock block : message.content()) {
            if (block instanceof LypiToolResultBlock toolResult) {
                ObjectNode node = objectMapper.createObjectNode();
                node.put("role", "tool");
                node.put("tool_call_id", toolResult.toolUseId());
                node.put("content", toolResult.text());
                messages.add(node);
            } else {
                ObjectNode node = objectMapper.createObjectNode();
                node.put("role", role(message.role()));
                node.put("content", blockText(block));
                messages.add(node);
            }
        }
    }

    private String role(LypiRole role) {
        return switch (role) {
            case USER, TOOL_RESULT -> "user";
            case ASSISTANT -> "assistant";
            case SYSTEM_LOCAL -> "system";
        };
    }

    private String blockText(LypiContentBlock block) {
        return switch (block) {
            case LypiTextBlock text -> text.text();
            case LypiThinkingBlock thinking -> thinking.text();
            case LypiToolCallBlock toolCall -> toolCall.text();
            case LypiToolResultBlock toolResult -> toolResult.text();
            case LypiAttachmentBlock attachment -> {
                // NOTE: 当前仅将附件文本说明发送给 provider，尚未按 mediaType 构造多模态内容。
                // TODO: 支持 supportsImageInput 模型的多模态 image content 映射。
                yield attachment.text();
            }
            case LypiErrorBlock error -> error.text();
        };
    }

    private ArrayNode tools(LypiModelRequest request) {
        ArrayNode tools = objectMapper.createArrayNode();
        for (LypiToolSpec tool : request.tools()) {
            ObjectNode wrapper = objectMapper.createObjectNode();
            ObjectNode function = objectMapper.createObjectNode();
            wrapper.put("type", "function");
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.set("parameters", objectMapper.valueToTree(tool.inputSchema()));
            wrapper.set("function", function);
            tools.add(wrapper);
        }
        return tools;
    }

    private java.util.Optional<String> reasoning(ThinkingLevel level) {
        Map<String, Object> mapped = OpenAiCompatibleThinkingParameterMapper.map(level);
        Object effort = mapped.get("reasoning_effort");
        return effort == null ? java.util.Optional.empty() : java.util.Optional.of(effort.toString());
    }
}
