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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.Objects;

public final class OpenAiResponsesRequestBuilder {
    private final ObjectMapper objectMapper;

    public OpenAiResponsesRequestBuilder() {
        this(new ObjectMapper());
    }

    public OpenAiResponsesRequestBuilder(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * 构造 OpenAI Responses 请求体。
     *
     * Provider 鉴权信息由 transport 设置 header，不进入请求体。
     */
    public ObjectNode build(LypiModelRequest request, OpenAiProviderConfig config) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(config, "config");
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", request.model().modelId());
        body.put("stream", true);
        if (!request.systemPrompt().isBlank()) {
            body.put("instructions", request.systemPrompt());
        }
        request.options().maxOutputTokens().ifPresent(tokens -> body.put("max_output_tokens", tokens));
        request.options().temperature().ifPresent(temperature -> body.put("temperature", temperature));
        body.set("input", input(request));
        if (!request.tools().isEmpty()) {
            body.set("tools", tools(request));
        }
        reasoning(request.thinkingLevel()).ifPresent(reasoning -> body.set("reasoning", reasoning));
        return body;
    }

    private ArrayNode input(LypiModelRequest request) {
        ArrayNode input = objectMapper.createArrayNode();
        for (LypiMessage message : request.messages()) {
            input.add(message(message));
        }
        return input;
    }

    private ObjectNode message(LypiMessage message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("role", role(message.role()));
        ArrayNode content = objectMapper.createArrayNode();
        for (LypiContentBlock block : message.content()) {
            contentBlock(block, message.role(), content);
        }
        node.set("content", content);
        return node;
    }

    private String role(LypiRole role) {
        return switch (role) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case TOOL_RESULT -> "user";
            case SYSTEM_LOCAL -> "system";
        };
    }

    private void contentBlock(LypiContentBlock block, LypiRole role, ArrayNode content) {
        switch (block) {
            case LypiTextBlock text -> content.add(textContent(role, text.text()));
            case LypiThinkingBlock thinking -> content.add(textContent(role, thinking.text()));
            case LypiToolCallBlock toolCall -> content.add(textContent(role, toolCall.text()));
            case LypiToolResultBlock toolResult -> content.add(textContent(LypiRole.USER, toolResult.text()));
            case LypiAttachmentBlock attachment -> content.add(textContent(role, attachment.text()));
            case LypiErrorBlock error -> content.add(textContent(role, error.text()));
        }
    }

    private ObjectNode textContent(LypiRole role, String text) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", role == LypiRole.ASSISTANT ? "output_text" : "input_text");
        node.put("text", text);
        return node;
    }

    private ArrayNode tools(LypiModelRequest request) {
        ArrayNode tools = objectMapper.createArrayNode();
        for (LypiToolSpec tool : request.tools()) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("type", "function");
            node.put("name", tool.name());
            node.put("description", tool.description());
            node.set("parameters", objectMapper.valueToTree(tool.inputSchema()));
            tools.add(node);
        }
        return tools;
    }

    private java.util.Optional<ObjectNode> reasoning(ThinkingLevel level) {
        Map<String, Object> mapped = OpenAiCompatibleThinkingParameterMapper.map(level);
        Object effort = mapped.get("reasoning_effort");
        if (effort == null) {
            return java.util.Optional.empty();
        }
        ObjectNode reasoning = objectMapper.createObjectNode();
        reasoning.put("effort", effort.toString());
        return java.util.Optional.of(reasoning);
    }
}
