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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
        Optional<String> promptCacheKey = promptCacheKey(request);
        promptCacheKey.ifPresent(key -> body.put("prompt_cache_key", key));
        Optional<PreviousResponseState> previousResponseState = promptCacheKey.isPresent()
            ? Optional.empty()
            : previousResponseState(request);
        previousResponseState.ifPresent(state -> body.put("previous_response_id", state.previousResponseId()));
        body.set("input", input(request, previousResponseState));
        if (!request.tools().isEmpty()) {
            body.set("tools", tools(request));
        }
        reasoning(request.thinkingLevel()).ifPresent(reasoning -> body.set("reasoning", reasoning));
        return body;
    }

    /**
     * 构造 Responses WebSocket `response.create` 事件。
     *
     * WebSocket 传输使用事件 envelope，HTTP SSE 专用的 `stream` 字段不进入 payload。
     */
    public ObjectNode buildWebSocketCreateEvent(LypiModelRequest request, OpenAiProviderConfig config) {
        ObjectNode response = build(request, config);
        response.remove("stream");
        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "response.create");
        event.set("response", response);
        return event;
    }

    private ArrayNode input(LypiModelRequest request, Optional<PreviousResponseState> previousResponseState) {
        ArrayNode input = objectMapper.createArrayNode();
        List<LypiMessage> messages = request.messages();
        int startIndex = previousResponseState
            .flatMap(state -> indexAfterMessage(messages, state.messageId()))
            .orElse(0);
        for (LypiMessage message : messages.subList(startIndex, messages.size())) {
            appendMessage(input, message);
        }
        return input;
    }

    private Optional<Integer> indexAfterMessage(List<LypiMessage> messages, String messageId) {
        if (messageId.isBlank()) {
            return Optional.empty();
        }
        for (int i = 0; i < messages.size(); i++) {
            Object currentMessageId = messages.get(i).metadata().get("messageId");
            if (messageId.equals(String.valueOf(currentMessageId))) {
                return Optional.of(i + 1);
            }
        }
        return Optional.empty();
    }

    private void appendMessage(ArrayNode input, LypiMessage message) {
        for (LypiContentBlock block : message.content()) {
            if (block instanceof LypiToolCallBlock toolCall) {
                input.add(functionCall(toolCall));
            } else if (block instanceof LypiToolResultBlock toolResult) {
                input.add(functionCallOutput(toolResult));
            } else {
                input.add(message(message, block));
            }
        }
    }

    private ObjectNode message(LypiMessage message, LypiContentBlock block) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("role", role(message.role()));
        ArrayNode content = objectMapper.createArrayNode();
        contentBlock(block, message.role(), content);
        node.set("content", content);
        return node;
    }

    private ObjectNode functionCall(LypiToolCallBlock toolCall) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "function_call");
        node.put("call_id", toolCall.toolUseId());
        node.put("name", toolCall.toolName());
        node.put("arguments", arguments(toolCall));
        return node;
    }

    private ObjectNode functionCallOutput(LypiToolResultBlock toolResult) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "function_call_output");
        node.put("call_id", toolResult.toolUseId());
        node.put("output", toolResult.text());
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

    private String arguments(LypiToolCallBlock toolCall) {
        Object input = toolCall.metadata().get("input");
        if (input instanceof Map<?, ?> inputMap) {
            return objectMapper.valueToTree(inputMap).toString();
        }
        String text = toolCall.text();
        return text == null || text.isBlank() ? "{}" : text;
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

    private Optional<String> promptCacheKey(LypiModelRequest request) {
        Object key = request.metadata().get("promptCacheKey");
        if (key == null) {
            return Optional.empty();
        }
        String value = String.valueOf(key);
        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private Optional<PreviousResponseState> previousResponseState(LypiModelRequest request) {
        Object state = request.metadata().get("providerConversationState");
        if (!(state instanceof Map<?, ?> stateMap)) {
            return Optional.empty();
        }
        if (!"openai".equals(String.valueOf(stateMap.get("provider")))) {
            return Optional.empty();
        }
        if (!"responses".equals(String.valueOf(stateMap.get("style")))) {
            return Optional.empty();
        }
        String previousResponseId = String.valueOf(stateMap.get("previousResponseId"));
        if (previousResponseId.isBlank() || "null".equals(previousResponseId)) {
            return Optional.empty();
        }
        String messageId = String.valueOf(stateMap.get("messageId"));
        Optional<Integer> startIndex = indexAfterMessage(request.messages(), messageId);
        if (startIndex.isEmpty() || hasToolResult(request.messages().subList(startIndex.get(), request.messages().size()))) {
            return Optional.empty();
        }
        return Optional.of(new PreviousResponseState(previousResponseId, messageId));
    }

    private boolean hasToolResult(List<LypiMessage> messages) {
        return messages.stream().anyMatch(message -> message.role() == LypiRole.TOOL_RESULT);
    }

    private record PreviousResponseState(String previousResponseId, String messageId) {
    }
}
