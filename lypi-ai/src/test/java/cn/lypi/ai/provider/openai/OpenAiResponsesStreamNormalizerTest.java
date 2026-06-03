package cn.lypi.ai.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.model.AssistantDone;
import cn.lypi.contracts.model.AssistantError;
import cn.lypi.contracts.model.AssistantStart;
import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.model.TextDelta;
import cn.lypi.contracts.model.ThinkingDelta;
import cn.lypi.contracts.model.ToolCallDelta;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenAiResponsesStreamNormalizerTest {
    @Test
    void normalizesStartTextThinkingToolCallAndDoneEvents() {
        OpenAiResponsesStreamNormalizer normalizer = new OpenAiResponsesStreamNormalizer();

        List<AssistantStreamEvent> events = List.of(
            normalizer.normalize("""
                {"type":"response.created","response":{"id":"resp-1"}}
                """),
            normalizer.normalize("""
                {"type":"response.output_text.delta","delta":"hello"}
                """),
            normalizer.normalize("""
                {"type":"response.reasoning_summary_text.delta","delta":"thinking"}
                """),
            normalizer.normalize("""
                {"type":"response.function_call_arguments.delta","item_id":"call-1","name":"read_file","delta":"{\\"path\\""}
                """),
            normalizer.normalize("""
                {"type":"response.function_call_arguments.delta","item_id":"call-1","name":"read_file","delta":":\\"pom.xml\\"}"}
                """),
            normalizer.normalize("""
                {"type":"response.completed","response":{"usage":{"input_tokens":10,"output_tokens":5,"input_tokens_details":{"cached_tokens":2},"output_tokens_details":{"reasoning_tokens":3}}}}
                """)
        ).stream().flatMap(List::stream).toList();

        assertThat(events).containsExactly(
            new AssistantStart("resp-1"),
            new TextDelta("hello"),
            new ThinkingDelta("thinking"),
            new ToolCallDelta("call-1", "read_file", MapBuilder.map(), false),
            new ToolCallDelta("call-1", "read_file", MapBuilder.map("path", "pom.xml"), true),
            new AssistantDone(java.util.Optional.of(new cn.lypi.contracts.model.TokenUsage(10, 5, 2, 3)), java.util.Optional.of("stop"))
        );
    }

    @Test
    void generatesStableToolCallIdWhenProviderOmitsOne() {
        OpenAiResponsesStreamNormalizer normalizer = new OpenAiResponsesStreamNormalizer();

        List<AssistantStreamEvent> first = normalizer.normalize("""
            {"type":"response.function_call_arguments.delta","output_index":0,"name":"search","delta":"{}"}
            """);
        List<AssistantStreamEvent> second = normalizer.normalize("""
            {"type":"response.function_call_arguments.delta","output_index":0,"name":"search","delta":"{}"}
            """);

        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        assertThat(((ToolCallDelta) first.getFirst()).toolUseId()).isEqualTo(((ToolCallDelta) second.getFirst()).toolUseId());
    }

    @Test
    void normalizesProviderErrorsAndMalformedJson() {
        OpenAiResponsesStreamNormalizer normalizer = new OpenAiResponsesStreamNormalizer();

        assertThat(normalizer.normalize("""
            {"type":"error","error":{"code":"bad_request","message":"Bad request"}}
            """)).containsExactly(new AssistantError("bad_request", "Bad request"));
        assertThat(normalizer.normalize("{broken"))
            .singleElement()
            .isInstanceOfSatisfying(AssistantError.class, error -> {
                assertThat(error.errorId()).isEqualTo("provider.malformed_event");
                assertThat(error.message()).contains("Malformed");
            });
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
