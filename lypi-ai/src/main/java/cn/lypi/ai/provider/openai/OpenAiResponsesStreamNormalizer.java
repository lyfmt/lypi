package cn.lypi.ai.provider.openai;

import cn.lypi.contracts.model.AssistantDone;
import cn.lypi.contracts.model.AssistantError;
import cn.lypi.contracts.model.AssistantStart;
import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.model.TextDelta;
import cn.lypi.contracts.model.ThinkingDelta;
import cn.lypi.contracts.model.TokenUsage;
import cn.lypi.contracts.model.ToolCallDelta;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class OpenAiResponsesStreamNormalizer {
    private final ObjectMapper objectMapper;
    private final Map<String, ToolCallAccumulator> toolCalls = new LinkedHashMap<>();

    public OpenAiResponsesStreamNormalizer() {
        this(new ObjectMapper());
    }

    public OpenAiResponsesStreamNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 标准化单条 Responses stream data。
     *
     * 未识别事件返回空列表，provider 原始事件不会向上层泄漏。
     */
    public List<AssistantStreamEvent> normalize(String data) {
        String trimmed = data == null ? "" : data.trim();
        if (trimmed.isEmpty() || "[DONE]".equals(trimmed)) {
            return List.of();
        }
        JsonNode event;
        try {
            event = objectMapper.readTree(trimmed);
        } catch (JsonProcessingException exception) {
            return List.of(new AssistantError("provider.malformed_event", "Malformed provider event."));
        }
        String type = event.path("type").asText();
        return switch (type) {
            case "response.created" -> start(event);
            case "response.output_text.delta" -> textDelta(event);
            case "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> thinkingDelta(event);
            case "response.function_call_arguments.delta" -> toolCallDelta(event);
            case "response.completed" -> done(event);
            case "error", "response.failed", "response.incomplete" -> error(event);
            default -> List.of();
        };
    }

    private List<AssistantStreamEvent> start(JsonNode event) {
        String id = event.path("response").path("id").asText("assistant");
        return List.of(new AssistantStart(id));
    }

    private List<AssistantStreamEvent> textDelta(JsonNode event) {
        return List.of(new TextDelta(event.path("delta").asText()));
    }

    private List<AssistantStreamEvent> thinkingDelta(JsonNode event) {
        return List.of(new ThinkingDelta(event.path("delta").asText()));
    }

    private List<AssistantStreamEvent> toolCallDelta(JsonNode event) {
        String toolUseId = toolUseId(event);
        ToolCallAccumulator accumulator = toolCalls.computeIfAbsent(
            toolUseId,
            ignored -> new ToolCallAccumulator(toolUseId, event.path("name").asText(""))
        );
        accumulator.append(event.path("delta").asText(""));
        return List.of(new ToolCallDelta(
            accumulator.toolUseId,
            accumulator.toolName,
            accumulator.partialInput(),
            accumulator.complete()
        ));
    }

    private String toolUseId(JsonNode event) {
        String itemId = event.path("item_id").asText();
        if (!itemId.isBlank()) {
            return itemId;
        }
        String key = event.path("output_index").asText("0") + ":" + event.path("name").asText("tool");
        return StableToolCallIds.from(key);
    }

    private List<AssistantStreamEvent> done(JsonNode event) {
        JsonNode usage = event.path("response").path("usage");
        if (usage.isMissingNode()) {
            return List.of(new AssistantDone(Optional.empty(), Optional.of("stop")));
        }
        TokenUsage tokenUsage = new TokenUsage(
            usage.path("input_tokens").asLong(),
            usage.path("output_tokens").asLong(),
            usage.path("input_tokens_details").path("cached_tokens").asLong(),
            usage.path("output_tokens_details").path("reasoning_tokens").asLong()
        );
        return List.of(new AssistantDone(Optional.of(tokenUsage), Optional.of("stop")));
    }

    private List<AssistantStreamEvent> error(JsonNode event) {
        JsonNode error = event.path("error");
        String code = error.path("code").asText("provider.error");
        String message = error.path("message").asText("Provider stream failed.");
        return List.of(new AssistantError(code, message));
    }

    private final class ToolCallAccumulator {
        private final String toolUseId;
        private final String toolName;
        private final StringBuilder arguments = new StringBuilder();

        private ToolCallAccumulator(String toolUseId, String toolName) {
            this.toolUseId = toolUseId;
            this.toolName = toolName;
        }

        private void append(String delta) {
            arguments.append(delta);
        }

        private boolean complete() {
            return parseArguments().isPresent();
        }

        private Map<String, Object> partialInput() {
            return parseArguments().orElseGet(Map::of);
        }

        private Optional<Map<String, Object>> parseArguments() {
            try {
                JsonNode node = objectMapper.readTree(arguments.toString());
                if (!node.isObject()) {
                    return Optional.of(Map.of());
                }
                Map<String, Object> values = new LinkedHashMap<>();
                node.properties().forEach(entry -> values.put(entry.getKey(), javaValue(entry.getValue())));
                return Optional.of(values);
            } catch (JsonProcessingException exception) {
                return Optional.empty();
            }
        }

        private Object javaValue(JsonNode node) {
            if (node.isTextual()) {
                return node.asText();
            }
            if (node.isNumber()) {
                return node.numberValue();
            }
            if (node.isBoolean()) {
                return node.asBoolean();
            }
            if (node.isArray()) {
                List<Object> values = new ArrayList<>();
                node.forEach(value -> values.add(javaValue(value)));
                return values;
            }
            if (node.isObject()) {
                Map<String, Object> values = new LinkedHashMap<>();
                node.properties().forEach(entry -> values.put(entry.getKey(), javaValue(entry.getValue())));
                return values;
            }
            return null;
        }
    }
}
