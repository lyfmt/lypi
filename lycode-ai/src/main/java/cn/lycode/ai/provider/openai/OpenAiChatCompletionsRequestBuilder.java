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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class OpenAiChatCompletionsRequestBuilder {
    private static final String REASONING_CONTENT_COMPAT =
        "requires-reasoning-content-on-assistant-messages";

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
    public ObjectNode build(LyCodeModelRequest request, OpenAiProviderConfig config) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(config, "config");
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", request.model().modelId());
        body.put("stream", true);
        request.options().maxOutputTokens().ifPresent(tokens -> body.put("max_tokens", tokens));
        request.options().temperature().ifPresent(temperature -> body.put("temperature", temperature));
        promptCacheKey(request).ifPresent(key -> body.put("prompt_cache_key", key));
        body.set("messages", messages(request, config));
        if (!request.tools().isEmpty()) {
            body.set("tools", tools(request));
        }
        reasoning(request.thinkingLevel()).ifPresent(effort -> body.put("reasoning_effort", effort));
        return body;
    }

    private ArrayNode messages(LyCodeModelRequest request, OpenAiProviderConfig config) {
        ArrayNode messages = objectMapper.createArrayNode();
        if (!request.systemPrompt().isBlank()) {
            ObjectNode system = objectMapper.createObjectNode();
            system.put("role", "system");
            system.put("content", request.systemPrompt());
            messages.add(system);
        }
        boolean requiresReasoningContent = compatEnabled(config, REASONING_CONTENT_COMPAT);
        for (LyCodeMessage message : request.messages()) {
            appendMessage(messages, message, requiresReasoningContent);
        }
        return messages;
    }

    private void appendMessage(ArrayNode messages, LyCodeMessage message, boolean requiresReasoningContent) {
        if (message.role() == LyCodeRole.ASSISTANT) {
            messages.add(assistantMessage(message, requiresReasoningContent));
            return;
        }
        if (hasImageAttachment(message)) {
            appendMultimodalMessage(messages, message);
            return;
        }
        for (LyCodeContentBlock block : message.content()) {
            if (block instanceof LyCodeToolResultBlock toolResult) {
                messages.add(toolResultMessage(toolResult));
            } else {
                ObjectNode node = objectMapper.createObjectNode();
                node.put("role", role(message.role()));
                node.put("content", blockText(block));
                messages.add(node);
            }
        }
    }

    private void appendMultimodalMessage(ArrayNode messages, LyCodeMessage message) {
        if (message.role() == LyCodeRole.TOOL_RESULT) {
            message.content().stream()
                .filter(LyCodeToolResultBlock.class::isInstance)
                .map(LyCodeToolResultBlock.class::cast)
                .map(this::toolResultMessage)
                .forEach(messages::add);
        }
        List<LyCodeContentBlock> contentBlocks = message.content().stream()
            .filter(block -> !(block instanceof LyCodeToolResultBlock))
            .toList();
        if (contentBlocks.isEmpty()) {
            return;
        }
        ObjectNode node = objectMapper.createObjectNode();
        node.put("role", role(message.role()));
        node.set("content", multimodalContent(contentBlocks));
        messages.add(node);
    }

    private ObjectNode toolResultMessage(LyCodeToolResultBlock toolResult) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("role", "tool");
        node.put("tool_call_id", toolResult.toolUseId());
        node.put("content", toolResult.text());
        return node;
    }

    private ArrayNode multimodalContent(List<LyCodeContentBlock> blocks) {
        ArrayNode content = objectMapper.createArrayNode();
        for (LyCodeContentBlock block : blocks) {
            if (block instanceof LyCodeAttachmentBlock attachment) {
                Optional<String> url = imageUrl(attachment);
                if (url.isPresent()) {
                    content.add(imageContent(attachment, url.orElseThrow()));
                    continue;
                }
            }
            String text = blockText(block);
            if (text != null && !text.isBlank()) {
                ObjectNode part = objectMapper.createObjectNode();
                part.put("type", "text");
                part.put("text", text);
                content.add(part);
            }
        }
        return content;
    }

    private ObjectNode imageContent(LyCodeAttachmentBlock attachment, String url) {
        ObjectNode part = objectMapper.createObjectNode();
        part.put("type", "image_url");
        ObjectNode imageUrl = objectMapper.createObjectNode();
        imageUrl.put("url", url);
        imageUrl.put("detail", String.valueOf(attachment.metadata().getOrDefault("detail", "high")));
        part.set("image_url", imageUrl);
        return part;
    }

    private boolean hasImageAttachment(LyCodeMessage message) {
        return message.content().stream()
            .filter(LyCodeAttachmentBlock.class::isInstance)
            .map(LyCodeAttachmentBlock.class::cast)
            .anyMatch(attachment -> imageUrl(attachment).isPresent());
    }

    private Optional<String> imageUrl(LyCodeAttachmentBlock attachment) {
        Object rawUrl = attachment.metadata().get("imageUrl");
        if (rawUrl == null) {
            return Optional.empty();
        }
        String url = String.valueOf(rawUrl);
        return url.isBlank() ? Optional.empty() : Optional.of(url);
    }

    private ObjectNode assistantMessage(LyCodeMessage message, boolean requiresReasoningContent) {
        List<LyCodeToolCallBlock> toolCallBlocks = message.content().stream()
            .filter(LyCodeToolCallBlock.class::isInstance)
            .map(LyCodeToolCallBlock.class::cast)
            .toList();
        ObjectNode node = objectMapper.createObjectNode();
        node.put("role", "assistant");
        String content = assistantContent(message, requiresReasoningContent);
        if (content.isBlank()) {
            node.putNull("content");
        } else {
            node.put("content", content);
        }
        if (requiresReasoningContent) {
            node.put("reasoning_content", assistantThinking(message));
        }
        if (!toolCallBlocks.isEmpty()) {
            ArrayNode toolCalls = objectMapper.createArrayNode();
            for (LyCodeToolCallBlock block : toolCallBlocks) {
                toolCalls.add(toolCall(block));
            }
            node.set("tool_calls", toolCalls);
        }
        return node;
    }

    private String assistantContent(LyCodeMessage message, boolean excludesThinking) {
        return message.content().stream()
            .filter(block -> !(block instanceof LyCodeToolCallBlock))
            .filter(block -> !excludesThinking || !(block instanceof LyCodeThinkingBlock))
            .map(this::blockText)
            .filter(text -> text != null && !text.isBlank())
            .collect(java.util.stream.Collectors.joining("\n"));
    }

    private String assistantThinking(LyCodeMessage message) {
        return message.content().stream()
            .filter(LyCodeThinkingBlock.class::isInstance)
            .map(LyCodeThinkingBlock.class::cast)
            .map(LyCodeThinkingBlock::text)
            .filter(text -> text != null && !text.isBlank())
            .collect(java.util.stream.Collectors.joining("\n"));
    }

    private ObjectNode toolCall(LyCodeToolCallBlock toolCall) {
        ObjectNode wrapper = objectMapper.createObjectNode();
        ObjectNode function = objectMapper.createObjectNode();
        wrapper.put("id", toolCall.toolUseId());
        wrapper.put("type", "function");
        function.put("name", toolCall.toolName());
        function.put("arguments", arguments(toolCall));
        wrapper.set("function", function);
        return wrapper;
    }

    private String role(LyCodeRole role) {
        return switch (role) {
            case USER, TOOL_RESULT -> "user";
            case ASSISTANT -> "assistant";
            case SYSTEM_LOCAL -> "system";
        };
    }

    private String blockText(LyCodeContentBlock block) {
        return switch (block) {
            case LyCodeTextBlock text -> text.text();
            case LyCodeThinkingBlock thinking -> thinking.text();
            case LyCodeToolCallBlock toolCall -> toolCall.text();
            case LyCodeToolResultBlock toolResult -> toolResult.text();
            case LyCodeAttachmentBlock attachment -> attachment.text();
            case LyCodeErrorBlock error -> error.text();
        };
    }

    private String arguments(LyCodeToolCallBlock toolCall) {
        Object input = toolCall.metadata().get("input");
        if (input instanceof Map<?, ?> inputMap) {
            return objectMapper.valueToTree(inputMap).toString();
        }
        String text = toolCall.text();
        return text == null || text.isBlank() ? "{}" : text;
    }

    private ArrayNode tools(LyCodeModelRequest request) {
        ArrayNode tools = objectMapper.createArrayNode();
        for (LyCodeToolSpec tool : request.tools()) {
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

    private Optional<String> promptCacheKey(LyCodeModelRequest request) {
        Object key = request.metadata().get("promptCacheKey");
        if (key == null) {
            return Optional.empty();
        }
        String value = String.valueOf(key);
        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private boolean compatEnabled(OpenAiProviderConfig config, String key) {
        Object value = config.compat().get(key);
        if (value instanceof Boolean enabled) {
            return enabled;
        }
        return value instanceof String text && Boolean.parseBoolean(text);
    }
}
