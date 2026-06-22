package cn.lypi.ai.provider.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.model.AssistantDone;
import cn.lypi.contracts.model.AssistantError;
import cn.lypi.contracts.model.AssistantStart;
import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.model.TextDelta;
import cn.lypi.contracts.model.ThinkingDelta;
import cn.lypi.contracts.model.TokenUsage;
import cn.lypi.contracts.model.ToolCallDelta;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AnthropicMessagesStreamNormalizerTest {
    @Test
    void normalizesStartTextThinkingToolCallAndDoneEvents() {
        AnthropicMessagesStreamNormalizer normalizer = new AnthropicMessagesStreamNormalizer();

        List<AssistantStreamEvent> events = List.of(
            normalizer.normalize("""
                {"type":"message_start","message":{"id":"msg_1"}}
                """),
            normalizer.normalize("""
                {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hello"}}
                """),
            normalizer.normalize("""
                {"type":"content_block_delta","index":1,"delta":{"type":"thinking_delta","thinking":"reasoning"}}
                """),
            normalizer.normalize("""
                {"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_1","name":"read_file","input":{}}}
                """),
            normalizer.normalize("""
                {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\\"path\\""}}
                """),
            normalizer.normalize("""
                {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":":\\"pom.xml\\"}"}}
                """),
            normalizer.normalize("""
                {"type":"content_block_stop","index":2}
                """),
            normalizer.normalize("""
                {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"input_tokens":10,"output_tokens":5,"cache_read_input_tokens":2}}
                """),
            normalizer.normalize("""
                {"type":"message_stop"}
                """)
        ).stream().flatMap(List::stream).toList();

        assertThat(events).containsExactly(
            new AssistantStart("msg_1"),
            new TextDelta("hello"),
            new ThinkingDelta("reasoning"),
            new ToolCallDelta("toolu_1", "read_file", MapBuilder.map(), false),
            new ToolCallDelta("toolu_1", "read_file", MapBuilder.map("path", "pom.xml"), true),
            new AssistantDone(Optional.of(new TokenUsage(10, 5, 2, 0)), Optional.of("tool_use"))
        );
    }

    @Test
    void combinesMessageStartAndDeltaUsage() {
        AnthropicMessagesStreamNormalizer normalizer = new AnthropicMessagesStreamNormalizer();

        List<AssistantStreamEvent> events = List.of(
            normalizer.normalize("""
                {"type":"message_start","message":{"id":"msg_1","usage":{"input_tokens":2679,"cache_read_input_tokens":17,"output_tokens":3}}}
                """),
            normalizer.normalize("""
                {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":510,"cache_read_input_tokens":19}}
                """),
            normalizer.normalize("""
                {"type":"message_stop"}
                """)
        ).stream().flatMap(List::stream).toList();

        assertThat(events).containsExactly(
            new AssistantStart("msg_1"),
            new AssistantDone(Optional.of(new TokenUsage(2679, 510, 19, 0)), Optional.of("end_turn"))
        );
    }

    @Test
    void usesMessageStartUsageWhenNoDeltaUsageArrives() {
        AnthropicMessagesStreamNormalizer normalizer = new AnthropicMessagesStreamNormalizer();

        List<AssistantStreamEvent> events = List.of(
            normalizer.normalize("""
                {"type":"message_start","message":{"id":"msg_1","usage":{"input_tokens":41,"cache_read_input_tokens":7,"output_tokens":1}}}
                """),
            normalizer.normalize("""
                {"type":"message_stop"}
                """)
        ).stream().flatMap(List::stream).toList();

        assertThat(events).containsExactly(
            new AssistantStart("msg_1"),
            new AssistantDone(Optional.of(new TokenUsage(41, 1, 7, 0)), Optional.of("stop"))
        );
    }

    @Test
    void mergesSparseUsageAcrossMultipleDeltas() {
        AnthropicMessagesStreamNormalizer normalizer = new AnthropicMessagesStreamNormalizer();

        List<AssistantStreamEvent> events = List.of(
            normalizer.normalize("""
                {"type":"message_start","message":{"id":"msg_1","usage":{"input_tokens":100,"cache_read_input_tokens":5,"output_tokens":1}}}
                """),
            normalizer.normalize("""
                {"type":"message_delta","delta":{},"usage":{"output_tokens":8}}
                """),
            normalizer.normalize("""
                {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"cache_read_input_tokens":9}}
                """),
            normalizer.normalize("""
                {"type":"message_stop"}
                """)
        ).stream().flatMap(List::stream).toList();

        assertThat(events).containsExactly(
            new AssistantStart("msg_1"),
            new AssistantDone(Optional.of(new TokenUsage(100, 8, 9, 0)), Optional.of("end_turn"))
        );
    }

    @Test
    void ignoresCacheCreationTokensBecauseTokenUsageTracksCacheReads() {
        AnthropicMessagesStreamNormalizer normalizer = new AnthropicMessagesStreamNormalizer();

        List<AssistantStreamEvent> events = List.of(
            normalizer.normalize("""
                {"type":"message_start","message":{"id":"msg_1","usage":{"input_tokens":100,"cache_creation_input_tokens":37,"cache_read_input_tokens":5,"output_tokens":1}}}
                """),
            normalizer.normalize("""
                {"type":"message_stop"}
                """)
        ).stream().flatMap(List::stream).toList();

        assertThat(events).containsExactly(
            new AssistantStart("msg_1"),
            new AssistantDone(Optional.of(new TokenUsage(100, 1, 5, 0)), Optional.of("stop"))
        );
    }

    @Test
    void ignoresPingAndUnknownEvents() {
        AnthropicMessagesStreamNormalizer normalizer = new AnthropicMessagesStreamNormalizer();

        assertThat(normalizer.normalize("""
            {"type":"ping"}
            """)).isEmpty();
        assertThat(normalizer.normalize("""
            {"type":"content_block_delta","delta":{"type":"signature_delta","signature":"abc"}}
            """)).isEmpty();
        assertThat(normalizer.normalize("""
            {"type":"unknown_new_event"}
            """)).isEmpty();
    }

    @Test
    void normalizesProviderErrorsAndMalformedJson() {
        AnthropicMessagesStreamNormalizer normalizer = new AnthropicMessagesStreamNormalizer();

        assertThat(normalizer.normalize("""
            {"type":"error","error":{"type":"invalid_request_error","message":"Bad request"}}
            """)).containsExactly(new AssistantError("invalid_request_error", "Bad request"));
        assertThat(normalizer.normalize("{broken"))
            .singleElement()
            .isInstanceOfSatisfying(AssistantError.class, error -> {
                assertThat(error.errorId()).isEqualTo("provider.malformed_event");
                assertThat(error.message()).contains("Malformed");
            });
    }

    @Test
    void emitsDoneWithoutUsageWhenNoMessageDeltaArrived() {
        AnthropicMessagesStreamNormalizer normalizer = new AnthropicMessagesStreamNormalizer();

        assertThat(normalizer.normalize("""
            {"type":"message_stop"}
            """)).containsExactly(new AssistantDone(Optional.empty(), Optional.of("stop")));
    }

    private static final class MapBuilder {
        private MapBuilder() {
        }

        private static java.util.Map<String, Object> map(Object... values) {
            java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
            for (int i = 0; i < values.length; i += 2) {
                map.put(values[i].toString(), values[i + 1]);
            }
            return map;
        }
    }
}
