package cn.lycode.ai.provider.anthropic;

import cn.lycode.ai.spec.LyCodeAttachmentBlock;
import cn.lycode.ai.spec.LyCodeContentBlock;
import cn.lycode.ai.spec.LyCodeErrorBlock;
import cn.lycode.ai.spec.LyCodeGenerationOptions;
import cn.lycode.ai.spec.LyCodeMessage;
import cn.lycode.ai.spec.LyCodeModelRequest;
import cn.lycode.ai.spec.LyCodeRole;
import cn.lycode.ai.spec.LyCodeTextBlock;
import cn.lycode.ai.spec.LyCodeThinkingBlock;
import cn.lycode.ai.spec.LyCodeToolCallBlock;
import cn.lycode.ai.spec.LyCodeToolResultBlock;
import cn.lycode.ai.spec.LyCodeToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class AnthropicMessagesRequestBuilder {
    private final ObjectMapper objectMapper;

    public AnthropicMessagesRequestBuilder() {
        this(new ObjectMapper());
    }

    public AnthropicMessagesRequestBuilder(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * 构造 Anthropic Messages 请求体。
     *
     * Provider 鉴权信息由 transport 设置 header，不进入请求体。
     */
    public ObjectNode build(LyCodeModelRequest request, AnthropicProviderConfig config) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(config, "config");
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", request.model().modelId());
        body.put("stream", true);
        body.put("max_tokens", maxTokens(request.options()));
        request.options().temperature().ifPresent(temperature -> body.put("temperature", temperature));
        String system = systemPrompt(request);
        if (!system.isBlank()) {
            body.put("system", system);
        }
        body.set("messages", messages(request));
        if (!request.tools().isEmpty()) {
            body.set("tools", tools(request));
        }
        return body;
    }

    private String systemPrompt(LyCodeModelRequest request) {
        List<String> parts = new ArrayList<>();
        if (!request.systemPrompt().isBlank()) {
            parts.add(request.systemPrompt());
        }
        for (LyCodeMessage message : request.messages()) {
            if (message.role() != LyCodeRole.SYSTEM_LOCAL) {
                continue;
            }
            String text = textOnly(message);
            if (!text.isBlank()) {
                parts.add(text);
            }
        }
        return String.join("\n\n", parts);
    }

    private String textOnly(LyCodeMessage message) {
        List<String> parts = new ArrayList<>();
        for (LyCodeContentBlock block : message.content()) {
            switch (block) {
                case LyCodeTextBlock text when !text.text().isBlank() -> parts.add(text.text());
                case LyCodeThinkingBlock thinking when !thinking.text().isBlank() -> parts.add(thinking.text());
                case LyCodeAttachmentBlock attachment when !attachment.text().isBlank() -> parts.add(attachment.text());
                case LyCodeErrorBlock error when !error.text().isBlank() -> parts.add(error.text());
                case LyCodeToolCallBlock ignored -> {
                }
                case LyCodeToolResultBlock ignored -> {
                }
                default -> {
                }
            }
        }
        return String.join("\n\n", parts);
    }

    private int maxTokens(LyCodeGenerationOptions options) {
        return options.maxOutputTokens().orElse(4096);
    }

    private ArrayNode messages(LyCodeModelRequest request) {
        ArrayNode messages = objectMapper.createArrayNode();
        for (LyCodeMessage message : request.messages()) {
            if (message.role() == LyCodeRole.SYSTEM_LOCAL) {
                continue;
            }
            if (message.role() == LyCodeRole.TOOL_RESULT) {
                toolResultMessage(message).ifPresent(messages::add);
                continue;
            }
            ObjectNode node = objectMapper.createObjectNode();
            node.put("role", role(message.role()));
            ArrayNode content = objectMapper.createArrayNode();
            for (LyCodeContentBlock block : message.content()) {
                contentBlock(block).ifPresent(content::add);
            }
            if (content.isEmpty()) {
                continue;
            }
            node.set("content", content);
            messages.add(node);
        }
        return messages;
    }

    private String role(LyCodeRole role) {
        return role == LyCodeRole.ASSISTANT ? "assistant" : "user";
    }

    private Optional<ObjectNode> toolResultMessage(LyCodeMessage message) {
        ArrayNode content = objectMapper.createArrayNode();
        List<LyCodeAttachmentBlock> attachments = imageAttachments(message);
        for (LyCodeToolResultBlock toolResult : toolResults(message)) {
            content.add(toolResultBlock(toolResult, attachments));
        }
        if (content.isEmpty()) {
            return Optional.empty();
        }
        ObjectNode node = objectMapper.createObjectNode();
        node.put("role", "user");
        node.set("content", content);
        return Optional.of(node);
    }

    private Optional<JsonNode> contentBlock(LyCodeContentBlock block) {
        return switch (block) {
            case LyCodeTextBlock text -> Optional.of(textBlock(text.text()));
            case LyCodeThinkingBlock ignored -> Optional.empty();
            case LyCodeToolCallBlock toolCall -> Optional.of(toolUseBlock(toolCall));
            case LyCodeToolResultBlock toolResult -> Optional.of(toolResultBlock(toolResult, List.of()));
            case LyCodeAttachmentBlock attachment -> Optional.of(textBlock(attachment.text()));
            case LyCodeErrorBlock error -> Optional.of(textBlock(error.text()));
        };
    }

    private ObjectNode textBlock(String text) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "text");
        node.put("text", text);
        return node;
    }

    private ObjectNode toolUseBlock(LyCodeToolCallBlock toolCall) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "tool_use");
        node.put("id", toolCall.toolUseId());
        node.put("name", toolCall.toolName());
        node.set("input", input(toolCall));
        return node;
    }

    private JsonNode input(LyCodeToolCallBlock toolCall) {
        Object input = toolCall.metadata().get("input");
        if (input instanceof Map<?, ?> inputMap) {
            return objectMapper.valueToTree(inputMap);
        }
        String text = toolCall.text();
        if (text == null || text.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode parsed = objectMapper.readTree(text);
            return parsed.isObject() ? parsed : objectMapper.createObjectNode();
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            return objectMapper.createObjectNode();
        }
    }

    private ObjectNode toolResultBlock(LyCodeToolResultBlock toolResult, List<LyCodeAttachmentBlock> attachments) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "tool_result");
        node.put("tool_use_id", toolResult.toolUseId());
        if (attachments.isEmpty()) {
            node.put("content", toolResult.text());
        } else {
            ArrayNode content = objectMapper.createArrayNode();
            content.add(textBlock(toolResult.text()));
            for (LyCodeAttachmentBlock attachment : attachments) {
                imageBlock(attachment).ifPresent(content::add);
            }
            node.set("content", content);
        }
        if (toolResult.error()) {
            node.put("is_error", true);
        } else {
            node.put("is_error", false);
        }
        return node;
    }

    private Optional<ObjectNode> imageBlock(LyCodeAttachmentBlock attachment) {
        Object imageUrl = attachment.metadata().get("imageUrl");
        if (imageUrl == null) {
            return Optional.empty();
        }
        Optional<DataUrl> dataUrl = parseDataUrl(String.valueOf(imageUrl));
        if (dataUrl.isEmpty()) {
            return Optional.empty();
        }
        ObjectNode source = objectMapper.createObjectNode();
        source.put("type", "base64");
        source.put("media_type", dataUrl.get().mediaType());
        source.put("data", dataUrl.get().data());

        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "image");
        node.set("source", source);
        return Optional.of(node);
    }

    private Optional<DataUrl> parseDataUrl(String imageUrl) {
        if (!imageUrl.startsWith("data:")) {
            return Optional.empty();
        }
        int marker = imageUrl.indexOf(";base64,");
        if (marker <= "data:".length()) {
            return Optional.empty();
        }
        String mediaType = imageUrl.substring("data:".length(), marker);
        String data = imageUrl.substring(marker + ";base64,".length());
        if (mediaType.isBlank() || data.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new DataUrl(mediaType, data));
    }

    private List<LyCodeToolResultBlock> toolResults(LyCodeMessage message) {
        return message.content().stream()
            .filter(LyCodeToolResultBlock.class::isInstance)
            .map(LyCodeToolResultBlock.class::cast)
            .toList();
    }

    private List<LyCodeAttachmentBlock> imageAttachments(LyCodeMessage message) {
        return message.content().stream()
            .filter(LyCodeAttachmentBlock.class::isInstance)
            .map(LyCodeAttachmentBlock.class::cast)
            .filter(attachment -> attachment.metadata().get("imageUrl") != null)
            .toList();
    }

    private ArrayNode tools(LyCodeModelRequest request) {
        ArrayNode tools = objectMapper.createArrayNode();
        for (LyCodeToolSpec tool : request.tools()) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("name", tool.name());
            node.put("description", tool.description());
            node.set("input_schema", objectMapper.valueToTree(tool.inputSchema()));
            tools.add(node);
        }
        return tools;
    }

    private record DataUrl(String mediaType, String data) {
    }

}
