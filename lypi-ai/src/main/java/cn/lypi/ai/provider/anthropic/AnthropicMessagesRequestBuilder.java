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
import cn.lypi.contracts.model.ThinkingLevel;
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
        thinking(request.thinkingLevel(), maxTokens(request.options())).ifPresent(thinking -> body.set("thinking", thinking));
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

    private Optional<JsonNode> contentBlock(LypiContentBlock block) {
        return switch (block) {
            case LypiTextBlock text -> Optional.of(textBlock(text.text()));
            case LypiThinkingBlock ignored -> Optional.empty();
            case LypiToolCallBlock toolCall -> Optional.of(toolUseBlock(toolCall));
            case LypiToolResultBlock toolResult -> Optional.of(toolResultBlock(toolResult));
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

    private ObjectNode toolResultBlock(LypiToolResultBlock toolResult) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "tool_result");
        node.put("tool_use_id", toolResult.toolUseId());
        node.put("content", toolResult.text());
        if (toolResult.error()) {
            node.put("is_error", true);
        } else {
            node.put("is_error", false);
        }
        return node;
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

    private Optional<ObjectNode> thinking(ThinkingLevel level, int maxTokens) {
        if (level == ThinkingLevel.OFF) {
            return Optional.empty();
        }
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "enabled");
        node.put("budget_tokens", Math.max(1, Math.min(thinkingBudgetTokens(level), maxTokens - 1)));
        return Optional.of(node);
    }

    private int thinkingBudgetTokens(ThinkingLevel level) {
        return switch (level) {
            case OFF -> 0;
            case MINIMAL -> 1024;
            case LOW -> 2048;
            case MEDIUM -> 4096;
            case HIGH -> 8192;
            case XHIGH, MAX -> 16_384;
        };
    }
}
