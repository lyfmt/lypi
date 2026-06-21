package cn.lypi.ai.provider.anthropic;

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

public final class AnthropicMessagesStreamNormalizer {
    private final ObjectMapper objectMapper;
    private final Map<Integer, ToolUseAccumulator> toolUses = new LinkedHashMap<>();
    private TokenUsage usage;
    private String stopReason = "stop";
    private boolean doneEmitted;

    public AnthropicMessagesStreamNormalizer() {
        this(new ObjectMapper());
    }

    public AnthropicMessagesStreamNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 标准化单条 Anthropic Messages stream data。
     *
     * 未识别事件返回空列表，provider 原始事件不会向上层泄漏。
     */
    public List<AssistantStreamEvent> normalize(String data) {
        String trimmed = data == null ? "" : data.trim();
        if (trimmed.isEmpty()) {
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
            case "message_start" -> start(event);
            case "content_block_start" -> contentBlockStart(event);
            case "content_block_delta" -> contentBlockDelta(event);
            case "content_block_stop" -> contentBlockStop(event);
            case "message_delta" -> messageDelta(event);
            case "message_stop" -> done();
            case "error" -> error(event);
            default -> List.of();
        };
    }

    private List<AssistantStreamEvent> start(JsonNode event) {
        String id = event.path("message").path("id").asText("assistant");
        return List.of(new AssistantStart(id));
    }

    private List<AssistantStreamEvent> contentBlockStart(JsonNode event) {
        JsonNode contentBlock = event.path("content_block");
        if (!"tool_use".equals(contentBlock.path("type").asText())) {
            return List.of();
        }
        int index = event.path("index").asInt();
        ToolUseAccumulator accumulator = toolUses.computeIfAbsent(index, ignored -> new ToolUseAccumulator());
        accumulator.toolUseId = contentBlock.path("id").asText(accumulator.toolUseId);
        accumulator.toolName = contentBlock.path("name").asText(accumulator.toolName);
        JsonNode input = contentBlock.path("input");
        if (input.isObject() && !input.isEmpty()) {
            accumulator.replace(input.toString());
            return List.of(accumulator.event(false));
        }
        return List.of();
    }

    private List<AssistantStreamEvent> contentBlockDelta(JsonNode event) {
        JsonNode delta = event.path("delta");
        String deltaType = delta.path("type").asText();
        return switch (deltaType) {
            case "text_delta" -> List.of(new TextDelta(delta.path("text").asText()));
            case "thinking_delta" -> List.of(new ThinkingDelta(delta.path("thinking").asText()));
            case "input_json_delta" -> toolInputDelta(event, delta);
            default -> List.of();
        };
    }

    private List<AssistantStreamEvent> toolInputDelta(JsonNode event, JsonNode delta) {
        int index = event.path("index").asInt();
        ToolUseAccumulator accumulator = toolUses.computeIfAbsent(index, ignored -> new ToolUseAccumulator());
        accumulator.append(delta.path("partial_json").asText(""));
        if (accumulator.complete()) {
            return List.of(accumulator.event(true));
        }
        return List.of(accumulator.event(false));
    }

    private List<AssistantStreamEvent> contentBlockStop(JsonNode event) {
        int index = event.path("index").asInt();
        ToolUseAccumulator accumulator = toolUses.get(index);
        if (accumulator == null || accumulator.toolUseId.isBlank()) {
            return List.of();
        }
        return accumulator.completeEmitted ? List.of() : List.of(accumulator.event(true));
    }

    private List<AssistantStreamEvent> messageDelta(JsonNode event) {
        String reason = event.path("delta").path("stop_reason").asText();
        if (!reason.isBlank()) {
            stopReason = reason;
        }
        JsonNode eventUsage = event.path("usage");
        if (eventUsage.isObject()) {
            usage = new TokenUsage(
                eventUsage.path("input_tokens").asLong(),
                eventUsage.path("output_tokens").asLong(),
                eventUsage.path("cache_read_input_tokens").asLong(),
                0
            );
        }
        return List.of();
    }

    private List<AssistantStreamEvent> done() {
        if (doneEmitted) {
            return List.of();
        }
        doneEmitted = true;
        return List.of(new AssistantDone(Optional.ofNullable(usage), Optional.of(stopReason)));
    }

    private List<AssistantStreamEvent> error(JsonNode event) {
        JsonNode error = event.path("error");
        String code = error.path("type").asText("provider.error");
        String message = error.path("message").asText("Provider stream failed.");
        return List.of(new AssistantError(code, message));
    }

    private final class ToolUseAccumulator {
        private String toolUseId = "";
        private String toolName = "";
        private final StringBuilder input = new StringBuilder();
        private boolean completeEmitted;

        private void append(String delta) {
            input.append(delta);
        }

        private void replace(String value) {
            input.setLength(0);
            input.append(value);
        }

        private boolean complete() {
            return parseInput().isPresent();
        }

        private ToolCallDelta event(boolean forceComplete) {
            boolean complete = forceComplete || complete();
            completeEmitted = completeEmitted || complete;
            return new ToolCallDelta(toolUseId, toolName, parseInput().orElseGet(Map::of), complete);
        }

        private Optional<Map<String, Object>> parseInput() {
            try {
                JsonNode node = objectMapper.readTree(input.toString());
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
