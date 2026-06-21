package cn.lypi.ai.provider.anthropic;

import cn.lypi.ai.spec.LypiAttachmentBlock;
import cn.lypi.ai.spec.LypiContentBlock;
import cn.lypi.ai.spec.LypiErrorBlock;
import cn.lypi.ai.spec.LypiGenerationOptions;
import cn.lypi.ai.spec.LypiMessage;
import cn.lypi.ai.spec.LypiModelRequest;
import cn.lypi.ai.spec.LypiRole;
import cn.lypi.ai.spec.LypiTextBlock;
import cn.lypi.ai.spec.LypiThinkingBlock;
import cn.lypi.ai.spec.LypiToolCallBlock;
import cn.lypi.ai.spec.LypiToolResultBlock;
import cn.lypi.ai.spec.LypiToolSpec;
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
    public ObjectNode build(LypiModelRequest request, AnthropicProviderConfig config) {
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

    private String systemPrompt(LypiModelRequest request) {
        List<String> parts = new ArrayList<>();
        if (!request.systemPrompt().isBlank()) {
            parts.add(request.systemPrompt());
        }
        for (LypiMessage message : request.messages()) {
            if (message.role() != LypiRole.SYSTEM_LOCAL) {
                continue;
            }
            String text = textOnly(message);
            if (!text.isBlank()) {
                parts.add(text);
            }
        }
        return String.join("\n\n", parts);
    }

    private String textOnly(LypiMessage message) {
        List<String> parts = new ArrayList<>();
        for (LypiContentBlock block : message.content()) {
            switch (block) {
                case LypiTextBlock text when !text.text().isBlank() -> parts.add(text.text());
                case LypiThinkingBlock thinking when !thinking.text().isBlank() -> parts.add(thinking.text());
                case LypiAttachmentBlock attachment when !attachment.text().isBlank() -> parts.add(attachment.text());
                case LypiErrorBlock error when !error.text().isBlank() -> parts.add(error.text());
                case LypiToolCallBlock ignored -> {
                }
                case LypiToolResultBlock ignored -> {
                }
                default -> {
                }
            }
        }
        return String.join("\n\n", parts);
    }

    private int maxTokens(LypiGenerationOptions options) {
        return options.maxOutputTokens().orElse(4096);
    }

    private ArrayNode messages(LypiModelRequest request) {
        ArrayNode messages = objectMapper.createArrayNode();
        for (LypiMessage message : request.messages()) {
            if (message.role() == LypiRole.SYSTEM_LOCAL) {
                continue;
            }
            if (message.role() == LypiRole.TOOL_RESULT) {
                toolResultMessage(message).ifPresent(messages::add);
                continue;
            }
            ObjectNode node = objectMapper.createObjectNode();
            node.put("role", role(message.role()));
            ArrayNode content = objectMapper.createArrayNode();
            for (LypiContentBlock block : message.content()) {
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

    private String role(LypiRole role) {
        return role == LypiRole.ASSISTANT ? "assistant" : "user";
    }

    private Optional<ObjectNode> toolResultMessage(LypiMessage message) {
        ArrayNode content = objectMapper.createArrayNode();
        List<LypiAttachmentBlock> attachments = imageAttachments(message);
        for (LypiToolResultBlock toolResult : toolResults(message)) {
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

    private Optional<JsonNode> contentBlock(LypiContentBlock block) {
        return switch (block) {
            case LypiTextBlock text -> Optional.of(textBlock(text.text()));
            case LypiThinkingBlock ignored -> Optional.empty();
            case LypiToolCallBlock toolCall -> Optional.of(toolUseBlock(toolCall));
            case LypiToolResultBlock toolResult -> Optional.of(toolResultBlock(toolResult, List.of()));
            case LypiAttachmentBlock attachment -> Optional.of(textBlock(attachment.text()));
            case LypiErrorBlock error -> Optional.of(textBlock(error.text()));
        };
    }

    private ObjectNode textBlock(String text) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "text");
        node.put("text", text);
        return node;
    }

    private ObjectNode toolUseBlock(LypiToolCallBlock toolCall) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "tool_use");
        node.put("id", toolCall.toolUseId());
        node.put("name", toolCall.toolName());
        node.set("input", input(toolCall));
        return node;
    }

    private JsonNode input(LypiToolCallBlock toolCall) {
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

    private ObjectNode toolResultBlock(LypiToolResultBlock toolResult, List<LypiAttachmentBlock> attachments) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "tool_result");
        node.put("tool_use_id", toolResult.toolUseId());
        if (attachments.isEmpty()) {
            node.put("content", toolResult.text());
        } else {
            ArrayNode content = objectMapper.createArrayNode();
            content.add(textBlock(toolResult.text()));
            for (LypiAttachmentBlock attachment : attachments) {
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

    private Optional<ObjectNode> imageBlock(LypiAttachmentBlock attachment) {
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

    private List<LypiToolResultBlock> toolResults(LypiMessage message) {
        return message.content().stream()
            .filter(LypiToolResultBlock.class::isInstance)
            .map(LypiToolResultBlock.class::cast)
            .toList();
    }

    private List<LypiAttachmentBlock> imageAttachments(LypiMessage message) {
        return message.content().stream()
            .filter(LypiAttachmentBlock.class::isInstance)
            .map(LypiAttachmentBlock.class::cast)
            .filter(attachment -> attachment.metadata().get("imageUrl") != null)
            .toList();
    }

    private ArrayNode tools(LypiModelRequest request) {
        ArrayNode tools = objectMapper.createArrayNode();
        for (LypiToolSpec tool : request.tools()) {
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
