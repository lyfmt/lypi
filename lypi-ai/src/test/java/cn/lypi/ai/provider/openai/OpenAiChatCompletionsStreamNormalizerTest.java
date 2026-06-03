package cn.lypi.ai.provider.openai;

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

class OpenAiChatCompletionsStreamNormalizerTest {
    @Test
    void normalizesTextReasoningToolCallAndDoneChunks() {
        OpenAiChatCompletionsStreamNormalizer normalizer = new OpenAiChatCompletionsStreamNormalizer();

        List<AssistantStreamEvent> events = List.of(
            normalizer.normalize("""
                {"id":"chatcmpl-1","choices":[{"delta":{"content":"hello"}}]}
                """),
            normalizer.normalize("""
                {"choices":[{"delta":{"reasoning_content":"thinking"}}]}
                """),
            normalizer.normalize("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"id\":\"call-1\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\"\"}}]}}]}"),
            normalizer.normalize("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"id\":\"call-1\",\"function\":{\"arguments\":\":\\\"pom.xml\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}"),
            normalizer.normalize("""
                {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5,"prompt_tokens_details":{"cached_tokens":2},"completion_tokens_details":{"reasoning_tokens":3}}}
                """)
        ).stream().flatMap(List::stream).toList();

        assertThat(events).containsExactly(
            new AssistantStart("chatcmpl-1"),
            new TextDelta("hello"),
            new ThinkingDelta("thinking"),
            new ToolCallDelta("call-1", "read_file", java.util.Map.of(), false),
            new ToolCallDelta("call-1", "read_file", java.util.Map.of("path", "pom.xml"), true),
            new AssistantDone(Optional.of(new TokenUsage(10, 5, 2, 3)), Optional.of("stop"))
        );
    }

    @Test
    void treatsDoneMarkerAsDoneWithoutUsage() {
        OpenAiChatCompletionsStreamNormalizer normalizer = new OpenAiChatCompletionsStreamNormalizer();

        assertThat(normalizer.normalize("[DONE]"))
            .containsExactly(new AssistantDone(Optional.empty(), Optional.of("stop")));
    }

    @Test
    void doesNotEmitDuplicateDoneAfterUsageChunk() {
        OpenAiChatCompletionsStreamNormalizer normalizer = new OpenAiChatCompletionsStreamNormalizer();

        assertThat(normalizer.normalize("""
            {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5}}
            """)).containsExactly(new AssistantDone(Optional.of(new TokenUsage(10, 5, 0, 0)), Optional.of("stop")));
        assertThat(normalizer.normalize("[DONE]")).isEmpty();
    }

    @Test
    void reportsMalformedJson() {
        OpenAiChatCompletionsStreamNormalizer normalizer = new OpenAiChatCompletionsStreamNormalizer();

        assertThat(normalizer.normalize("{broken"))
            .singleElement()
            .isInstanceOfSatisfying(AssistantError.class, error -> {
                assertThat(error.errorId()).isEqualTo("provider.malformed_event");
                assertThat(error.message()).contains("Malformed");
            });
    }
}
