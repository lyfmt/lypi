package cn.lycode.ai.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lycode.contracts.model.AssistantDone;
import cn.lycode.contracts.model.AssistantError;
import cn.lycode.contracts.model.AssistantStart;
import cn.lycode.contracts.model.AssistantStreamEvent;
import cn.lycode.contracts.model.TextDelta;
import cn.lycode.contracts.model.ThinkingDelta;
import cn.lycode.contracts.model.TokenUsage;
import cn.lycode.contracts.model.ToolCallDelta;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OpenAiChatCompletionsStreamNormalizerTest {
    @Test
    void normalizesCompatibleReasoningShapesWithoutDuplicateDetails() {
        OpenAiChatCompletionsStreamNormalizer normalizer = new OpenAiChatCompletionsStreamNormalizer();

        List<AssistantStreamEvent> events = List.of(
            normalizer.normalize("""
                {"choices":[{"index":0,"delta":{"reasoning":"think","reasoning_details":[{"type":"reasoning.text","text":"think"}]}}]}
                """),
            normalizer.normalize("""
                {"choices":[{"index":0,"delta":{"reasoning_text":"fallback"}}]}
                """),
            normalizer.normalize("""
                {"choices":[{"index":0,"delta":{"reasoning_details":[{"type":"reasoning.encrypted","data":"ignored"},{"type":"reasoning.text","text":"details-only"}]}}]}
                """),
            normalizer.normalize("""
                {"choices":[{"index":1,"delta":{"content":"answer"}}]}
                """)
        ).stream().flatMap(List::stream).toList();

        assertThat(events).containsExactly(
            new ThinkingDelta("think"),
            new ThinkingDelta("fallback"),
            new ThinkingDelta("details-only"),
            new TextDelta("answer")
        );
    }

    @Test
    void separatesThinkingTagsAcrossContentChunkBoundaries() {
        OpenAiChatCompletionsStreamNormalizer normalizer = new OpenAiChatCompletionsStreamNormalizer();

        List<AssistantStreamEvent> events = List.of(
            normalizer.normalize("{\"choices\":[{\"delta\":{\"content\":\"<thi\"}}]}"),
            normalizer.normalize("{\"choices\":[{\"delta\":{\"content\":\"nk>plan</th\"}}]}"),
            normalizer.normalize("{\"choices\":[{\"delta\":{\"content\":\"ink>answer\"}}]}"),
            normalizer.normalize("[DONE]")
        ).stream().flatMap(List::stream).toList();

        assertThat(events).containsExactly(
            new ThinkingDelta("plan"),
            new TextDelta("answer"),
            new AssistantDone(Optional.empty(), Optional.of("stop"))
        );
    }

    @Test
    void flushesUnclosedTaggedContentInTheCurrentModeOnDone() {
        OpenAiChatCompletionsStreamNormalizer normalizer = new OpenAiChatCompletionsStreamNormalizer();

        List<AssistantStreamEvent> events = List.of(
            normalizer.normalize("{\"choices\":[{\"delta\":{\"content\":\"before<think>unfinished</th\"}}]}"),
            normalizer.normalize("[DONE]")
        ).stream().flatMap(List::stream).toList();

        assertThat(events).containsExactly(
            new TextDelta("before"),
            new ThinkingDelta("unfinished"),
            new ThinkingDelta("</th"),
            new AssistantDone(Optional.empty(), Optional.of("stop"))
        );
    }

    @Test
    void flushesUnclosedTaggedContentBeforeUsageCompletion() {
        OpenAiChatCompletionsStreamNormalizer normalizer = new OpenAiChatCompletionsStreamNormalizer();

        List<AssistantStreamEvent> events = List.of(
            normalizer.normalize("{\"choices\":[{\"delta\":{\"content\":\"<think>plan</th\"}}]}"),
            normalizer.normalize("""
                {"choices":[],"usage":{"prompt_tokens":4,"completion_tokens":2}}
                """)
        ).stream().flatMap(List::stream).toList();

        assertThat(events).containsExactly(
            new ThinkingDelta("plan"),
            new ThinkingDelta("</th"),
            new AssistantDone(Optional.of(new TokenUsage(4, 2, 0, 0)), Optional.of("stop"))
        );
    }

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
    void keepsToolCallAccumulatorWhenOnlyFirstChunkHasIdAndName() {
        OpenAiChatCompletionsStreamNormalizer normalizer = new OpenAiChatCompletionsStreamNormalizer();

        List<AssistantStreamEvent> events = List.of(
            normalizer.normalize("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\"\"}}]}}]}"),
            normalizer.normalize("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\":\\\"pom.xml\\\"}\"}}]}}]}")
        ).stream().flatMap(List::stream).toList();

        assertThat(events).containsExactly(
            new ToolCallDelta("call-1", "read_file", java.util.Map.of(), false),
            new ToolCallDelta("call-1", "read_file", java.util.Map.of("path", "pom.xml"), true)
        );
    }

    @Test
    void preservesNullValuesInToolCallArguments() {
        OpenAiChatCompletionsStreamNormalizer normalizer = new OpenAiChatCompletionsStreamNormalizer();

        List<AssistantStreamEvent> events = normalizer.normalize("""
            {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","function":{"name":"read_file","arguments":"{\\"path\\":null}"}}]}}]}
            """);

        assertThat(events).singleElement().isInstanceOfSatisfying(ToolCallDelta.class, delta -> {
            assertThat(delta.partialInput()).containsKey("path");
            assertThat(delta.partialInput().get("path")).isNull();
            assertThat(delta.complete()).isTrue();
        });
    }

    @Test
    void ignoresNullUsageAndKeepsNormalizingDeltas() {
        OpenAiChatCompletionsStreamNormalizer normalizer = new OpenAiChatCompletionsStreamNormalizer();

        List<AssistantStreamEvent> events = List.of(
            normalizer.normalize("""
                {"id":"chatcmpl-1","choices":[{"delta":{"content":"hello"}}],"usage":null}
                """),
            normalizer.normalize("""
                {"choices":[{"delta":{"content":" world"}}]}
                """),
            normalizer.normalize("[DONE]")
        ).stream().flatMap(List::stream).toList();

        assertThat(events).containsExactly(
            new AssistantStart("chatcmpl-1"),
            new TextDelta("hello"),
            new TextDelta(" world"),
            new AssistantDone(Optional.empty(), Optional.of("stop"))
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
