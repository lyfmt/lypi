package cn.lycode.ai.provider.openai;

import cn.lycode.ai.OpenAiCompatibleThinkingParameterMapper;
import cn.lycode.ai.spec.LyCodeAttachmentBlock;
import cn.lycode.ai.spec.LyCodeContentBlock;
import cn.lycode.ai.spec.LyCodeErrorBlock;
import cn.lycode.ai.spec.LyCodeMessage;
import cn.lycode.ai.spec.LyCodeModelRequest;
import cn.lycode.ai.spec.LyCodeRole;
import cn.lycode.ai.spec.LyCodeTextBlock;
import cn.lycode.ai.spec.LyCodeThinkingBlock;
import cn.lycode.ai.spec.LyCodeToolCallBlock;
import cn.lycode.ai.spec.LyCodeToolResultBlock;
import cn.lycode.ai.spec.LyCodeToolSpec;
import cn.lycode.contracts.model.ThinkingLevel;
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
    public ObjectNode build(LyCodeModelRequest request, OpenAiProviderConfig config) {
        return build(request, config, OpenAiResponsesRequestOptions.defaults());
    }

    /**
     * 构造 OpenAI Responses 请求体。
     *
     * Provider 鉴权信息由 transport 设置 header，不进入请求体。
     */
    public ObjectNode build(LyCodeModelRequest request, OpenAiProviderConfig config, OpenAiResponsesRequestOptions options) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(options, "options");
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
        Optional<PreviousResponseState> previousResponseState = options.previousResponseStateEnabled()
            ? previousResponseState(request)
            : Optional.empty();
        previousResponseState.ifPresent(state -> body.put("previous_response_id", state.previousResponseId()));
        body.set("input", input(request, previousResponseState, options.replayToolInteractionsAsProtocolItems()));
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
    public ObjectNode buildWebSocketCreateEvent(LyCodeModelRequest request, OpenAiProviderConfig config) {
        ObjectNode response = build(request, config);
        response.remove("stream");
        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "response.create");
        event.set("response", response);
        return event;
    }

    private ArrayNode input(
        LyCodeModelRequest request,
        Optional<PreviousResponseState> previousResponseState,
        boolean replayToolInteractionsAsProtocolItems
    ) {
        ArrayNode input = objectMapper.createArrayNode();
        List<LyCodeMessage> messages = request.messages();
        int startIndex = previousResponseState
            .flatMap(state -> indexAfterMessage(messages, state.messageId()))
            .orElse(0);
        for (LyCodeMessage message : messages.subList(startIndex, messages.size())) {
            appendMessage(input, message, previousResponseState.isPresent(), replayToolInteractionsAsProtocolItems);
        }
        return input;
    }

    private Optional<Integer> indexAfterMessage(List<LyCodeMessage> messages, String messageId) {
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

    private void appendMessage(
        ArrayNode input,
        LyCodeMessage message,
        boolean allowProtocolToolItems,
        boolean replayToolInteractionsAsProtocolItems
    ) {
        if (message.role() == LyCodeRole.TOOL_RESULT) {
            appendToolResultMessage(input, message, allowProtocolToolItems, replayToolInteractionsAsProtocolItems);
            return;
        }
        for (LyCodeContentBlock block : message.content()) {
            if (block instanceof LyCodeToolCallBlock toolCall) {
                if (replayToolInteractionsAsProtocolItems || allowProtocolToolItems && pendingToolCall(toolCall)) {
                    input.add(functionCall(toolCall));
                }
            } else if (block instanceof LyCodeToolResultBlock toolResult) {
                if (replayToolInteractionsAsProtocolItems || allowProtocolToolItems && pendingToolOutput(toolResult)) {
                    input.add(functionCallOutput(toolResult));
                }
            } else {
                input.add(message(message, block));
            }
        }
    }

    private void appendToolResultMessage(
        ArrayNode input,
        LyCodeMessage message,
        boolean allowProtocolToolItems,
        boolean replayToolInteractionsAsProtocolItems
    ) {
        Optional<LyCodeToolResultBlock> toolResult = message.content().stream()
            .filter(LyCodeToolResultBlock.class::isInstance)
            .map(LyCodeToolResultBlock.class::cast)
            .findFirst();
        if (toolResult.isPresent()
            && (replayToolInteractionsAsProtocolItems
                || allowProtocolToolItems && pendingToolOutput(toolResult.get()))) {
            input.add(functionCallOutput(toolResult.get(), imageAttachments(message)));
            return;
        }
        for (LyCodeContentBlock block : message.content()) {
            if (!(block instanceof LyCodeToolResultBlock)) {
                input.add(message(message, block));
            }
        }
    }

    private ObjectNode message(LyCodeMessage message, LyCodeContentBlock block) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("role", role(message.role()));
        ArrayNode content = objectMapper.createArrayNode();
        contentBlock(block, message.role(), content);
        node.set("content", content);
        return node;
    }

    private ObjectNode functionCall(LyCodeToolCallBlock toolCall) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "function_call");
        node.put("call_id", toolCall.toolUseId());
        node.put("name", toolCall.toolName());
        node.put("arguments", arguments(toolCall));
        return node;
    }

    private ObjectNode functionCallOutput(LyCodeToolResultBlock toolResult) {
        return functionCallOutput(toolResult, List.of());
    }

    private ObjectNode functionCallOutput(LyCodeToolResultBlock toolResult, List<LyCodeAttachmentBlock> imageAttachments) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "function_call_output");
        node.put("call_id", toolResult.toolUseId());
        if (imageAttachments.isEmpty()) {
            node.put("output", toolResult.text());
        } else {
            ArrayNode output = objectMapper.createArrayNode();
            output.add(textContent(LyCodeRole.USER, toolResult.text()));
            for (LyCodeAttachmentBlock attachment : imageAttachments) {
                output.add(imageContent(attachment));
            }
            node.set("output", output);
        }
        return node;
    }

    private String role(LyCodeRole role) {
        return switch (role) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case TOOL_RESULT -> "user";
            case SYSTEM_LOCAL -> "system";
        };
    }

    private void contentBlock(LyCodeContentBlock block, LyCodeRole role, ArrayNode content) {
        switch (block) {
            case LyCodeTextBlock text -> content.add(textContent(role, text.text()));
            case LyCodeThinkingBlock thinking -> content.add(textContent(role, thinking.text()));
            case LyCodeToolCallBlock toolCall -> content.add(textContent(role, toolCall.text()));
            case LyCodeToolResultBlock toolResult -> content.add(textContent(LyCodeRole.USER, toolResult.text()));
            case LyCodeAttachmentBlock attachment -> content.add(textContent(role, attachment.text()));
            case LyCodeErrorBlock error -> content.add(textContent(role, error.text()));
        }
    }

    private ObjectNode textContent(LyCodeRole role, String text) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", role == LyCodeRole.ASSISTANT ? "output_text" : "input_text");
        node.put("text", text);
        return node;
    }

    private ObjectNode imageContent(LyCodeAttachmentBlock attachment) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "input_image");
        node.put("image_url", String.valueOf(attachment.metadata().get("imageUrl")));
        Object detail = attachment.metadata().getOrDefault("detail", "high");
        node.put("detail", String.valueOf(detail));
        return node;
    }

    private List<LyCodeAttachmentBlock> imageAttachments(LyCodeMessage message) {
        return message.content().stream()
            .filter(LyCodeAttachmentBlock.class::isInstance)
            .map(LyCodeAttachmentBlock.class::cast)
            .filter(attachment -> attachment.metadata().get("imageUrl") != null)
            .toList();
    }

    private String arguments(LyCodeToolCallBlock toolCall) {
        Object input = toolCall.metadata().get("input");
        if (input instanceof Map<?, ?> inputMap) {
            return objectMapper.valueToTree(inputMap).toString();
        }
        String text = toolCall.text();
        return text == null || text.isBlank() ? "{}" : text;
    }

    private boolean pendingToolCall(LyCodeToolCallBlock toolCall) {
        return Boolean.TRUE.equals(toolCall.metadata().get("openaiPendingToolCall"));
    }

    private boolean pendingToolOutput(LyCodeToolResultBlock toolResult) {
        return Boolean.TRUE.equals(toolResult.metadata().get("openaiPendingToolOutput"));
    }

    private ArrayNode tools(LyCodeModelRequest request) {
        ArrayNode tools = objectMapper.createArrayNode();
        for (LyCodeToolSpec tool : request.tools()) {
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
        reasoning.put("summary", "auto");
        return java.util.Optional.of(reasoning);
    }

    private Optional<String> promptCacheKey(LyCodeModelRequest request) {
        Object key = request.metadata().get("promptCacheKey");
        if (key == null) {
            return Optional.empty();
        }
        String value = String.valueOf(key);
        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private Optional<PreviousResponseState> previousResponseState(LyCodeModelRequest request) {
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
        if (startIndex.isEmpty() || hasHistoricalToolResult(request.messages().subList(startIndex.get(), request.messages().size()))) {
            return Optional.empty();
        }
        return Optional.of(new PreviousResponseState(previousResponseId, messageId));
    }

    private boolean hasHistoricalToolResult(List<LyCodeMessage> messages) {
        return messages.stream().anyMatch(message ->
            message.role() == LyCodeRole.TOOL_RESULT
                && message.content().stream()
                    .filter(LyCodeToolResultBlock.class::isInstance)
                    .map(LyCodeToolResultBlock.class::cast)
                    .noneMatch(this::pendingToolOutput)
        );
    }

    private record PreviousResponseState(String previousResponseId, String messageId) {
    }
}
