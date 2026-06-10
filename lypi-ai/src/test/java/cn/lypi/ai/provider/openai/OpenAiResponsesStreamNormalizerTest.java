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
import java.util.Optional;
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
                {"type":"response.output_item.added","output_index":1,"item":{"type":"function_call","id":"item-1","call_id":"call-1","name":"read_file"}}
                """),
            normalizer.normalize("""
                {"type":"response.function_call_arguments.delta","item_id":"item-1","output_index":1,"delta":"{\\"path\\""}
                """),
            normalizer.normalize("""
                {"type":"response.function_call_arguments.done","item_id":"item-1","output_index":1,"arguments":"{\\"path\\":\\"pom.xml\\"}"}
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
    void capturesCompletedResponseIdAsConversationState() {
        OpenAiResponsesStreamNormalizer normalizer = new OpenAiResponsesStreamNormalizer();

        normalizer.normalize("""
            {"type":"response.created","response":{"id":"resp-created"}}
            """);
        normalizer.normalize("""
            {"type":"response.completed","response":{"id":"resp-completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"hi"}]}]}}
            """);

        assertThat(normalizer.providerConversationState())
            .hasValueSatisfying(state -> {
                assertThat(state.provider()).isEqualTo("openai");
                assertThat(state.style()).isEqualTo("responses");
                assertThat(state.previousResponseId()).isEqualTo(Optional.of("resp-completed"));
            });
    }

    @Test
    void doesNotExposeConversationStateBeforeSuccessfulCompletion() {
        OpenAiResponsesStreamNormalizer normalizer = new OpenAiResponsesStreamNormalizer();

        normalizer.normalize("""
            {"type":"response.created","response":{"id":"resp-created"}}
            """);

        assertThat(normalizer.providerConversationState()).isEmpty();
    }

    @Test
    void doesNotExposeConversationStateForFailedOrIncompleteResponse() {
        OpenAiResponsesStreamNormalizer failed = new OpenAiResponsesStreamNormalizer();
        failed.normalize("""
            {"type":"response.created","response":{"id":"resp-failed"}}
            """);
        failed.normalize("""
            {"type":"response.failed","response":{"id":"resp-failed"},"error":{"code":"server_error","message":"failed"}}
            """);

        OpenAiResponsesStreamNormalizer incomplete = new OpenAiResponsesStreamNormalizer();
        incomplete.normalize("""
            {"type":"response.created","response":{"id":"resp-incomplete"}}
            """);
        incomplete.normalize("""
            {"type":"response.incomplete","response":{"id":"resp-incomplete"},"error":{"code":"incomplete","message":"incomplete"}}
            """);

        assertThat(failed.providerConversationState()).isEmpty();
        assertThat(incomplete.providerConversationState()).isEmpty();
    }

    @Test
    void generatesStableToolCallIdWhenProviderOmitsOne() {
        OpenAiResponsesStreamNormalizer normalizer = new OpenAiResponsesStreamNormalizer();

        List<AssistantStreamEvent> first = normalizer.normalize("""
            {"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","name":"search"}}
            """);
        List<AssistantStreamEvent> second = normalizer.normalize("""
            {"type":"response.function_call_arguments.done","output_index":0,"arguments":"{}"}
            """);

        assertThat(first).isEmpty();
        assertThat(second).hasSize(1);
        assertThat(((ToolCallDelta) second.getFirst()).toolUseId()).isNotBlank();
        assertThat(((ToolCallDelta) second.getFirst()).toolName()).isEqualTo("search");
    }

    @Test
    void preservesNullValuesInToolCallArguments() {
        OpenAiResponsesStreamNormalizer normalizer = new OpenAiResponsesStreamNormalizer();

        normalizer.normalize("""
            {"type":"response.output_item.added","output_index":1,"item":{"type":"function_call","id":"item-1","call_id":"call-1","name":"read_file"}}
            """);
        List<AssistantStreamEvent> events = normalizer.normalize("""
            {"type":"response.function_call_arguments.done","item_id":"item-1","output_index":1,"arguments":"{\\"path\\":null}"}
            """);

        assertThat(events).singleElement().isInstanceOfSatisfying(ToolCallDelta.class, delta -> {
            assertThat(delta.partialInput()).containsKey("path");
            assertThat(delta.partialInput().get("path")).isNull();
            assertThat(delta.complete()).isTrue();
        });
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
