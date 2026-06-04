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

public final class OpenAiChatCompletionsStreamNormalizer {
    private final ObjectMapper objectMapper;
    private final Map<String, ToolCallAccumulator> toolCalls = new LinkedHashMap<>();
    private boolean started;
    private boolean doneEmitted;

    public OpenAiChatCompletionsStreamNormalizer() {
        this(new ObjectMapper());
    }

    public OpenAiChatCompletionsStreamNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 标准化单条 Chat Completions stream data。
     *
     * `[DONE]` 会转为无 usage 的完成事件，usage chunk 可补充 token 统计。
     */
    public List<AssistantStreamEvent> normalize(String data) {
        String trimmed = data == null ? "" : data.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        if ("[DONE]".equals(trimmed)) {
            return done(Optional.empty());
        }
        JsonNode event;
        try {
            event = objectMapper.readTree(trimmed);
        } catch (JsonProcessingException exception) {
            return List.of(new AssistantError("provider.malformed_event", "Malformed provider event."));
        }
        if (event.has("error")) {
            JsonNode error = event.path("error");
            return List.of(new AssistantError(
                error.path("code").asText("provider.error"),
                error.path("message").asText("Provider stream failed.")
            ));
        }
        if (event.has("usage")) {
            return doneWithUsage(event.path("usage"));
        }
        List<AssistantStreamEvent> normalized = new ArrayList<>();
        if (!started && event.hasNonNull("id")) {
            normalized.add(new AssistantStart(event.path("id").asText()));
            started = true;
        }
        JsonNode choice = event.path("choices").isArray() && !event.path("choices").isEmpty()
            ? event.path("choices").get(0)
            : null;
        if (choice == null) {
            return normalized;
        }
        JsonNode delta = choice.path("delta");
        if (delta.hasNonNull("content")) {
            normalized.add(new TextDelta(delta.path("content").asText()));
        }
        if (delta.hasNonNull("reasoning_content")) {
            normalized.add(new ThinkingDelta(delta.path("reasoning_content").asText()));
        }
        if (delta.path("tool_calls").isArray()) {
            delta.path("tool_calls").forEach(toolCall -> normalized.add(toolCallDelta(toolCall)));
        }
        return normalized;
    }

    private ToolCallDelta toolCallDelta(JsonNode toolCall) {
        String key = "index:" + toolCall.path("index").asText("0");
        String rawId = toolCall.path("id").asText();
        String fallbackId = StableToolCallIds.from(key + ":" + toolCall.path("function").path("name").asText("tool"));
        String name = toolCall.path("function").path("name").asText("");
        ToolCallAccumulator accumulator = toolCalls.computeIfAbsent(key, ignored -> new ToolCallAccumulator(fallbackId, name));
        if (!rawId.isBlank()) {
            accumulator.toolUseId = rawId;
        }
        if (!name.isBlank()) {
            accumulator.toolName = name;
        }
        accumulator.append(toolCall.path("function").path("arguments").asText(""));
        return new ToolCallDelta(
            accumulator.toolUseId,
            accumulator.toolName,
            accumulator.partialInput(),
            accumulator.complete()
        );
    }

    private List<AssistantStreamEvent> doneWithUsage(JsonNode usage) {
        TokenUsage tokenUsage = new TokenUsage(
            usage.path("prompt_tokens").asLong(),
            usage.path("completion_tokens").asLong(),
            usage.path("prompt_tokens_details").path("cached_tokens").asLong(),
            usage.path("completion_tokens_details").path("reasoning_tokens").asLong()
        );
        return done(Optional.of(tokenUsage));
    }

    private List<AssistantStreamEvent> done(Optional<TokenUsage> usage) {
        if (doneEmitted) {
            return List.of();
        }
        doneEmitted = true;
        return List.of(new AssistantDone(usage, Optional.of("stop")));
    }

    private final class ToolCallAccumulator {
        private String toolUseId;
        private String toolName;
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
