package cn.lypi.ai.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lypi.ai.provider.ListProviderEventStream;
import cn.lypi.ai.provider.ProviderEventStream;
import cn.lypi.ai.provider.ProviderRawEvent;
import cn.lypi.ai.provider.ProviderRequest;
import cn.lypi.ai.provider.ProviderTransport;
import cn.lypi.ai.provider.RequestStyle;
import cn.lypi.ai.provider.TransportMode;
import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.error.ModelProviderException;
import cn.lypi.contracts.model.AssistantDone;
import cn.lypi.contracts.model.AssistantEventStream;
import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.model.ModelDescriptor;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ProviderRetryNotice;
import cn.lypi.contracts.model.TextDelta;
import cn.lypi.contracts.model.ThinkingDelta;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.runtime.AiProviderRuntimePort;
import cn.lypi.contracts.runtime.AiStreamOptions;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.tool.ToolDescriptor;
import cn.lypi.contracts.tool.ToolRegistrySnapshot;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class OpenAiCompatibleProviderAdapterTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void triesWebSocketResponsesThenSseResponsesThenChatCompletionsFallback() throws Exception {
        RecordingTransport websocket = RecordingTransport.fail("WebSocket handshake failed");
        RecordingTransport sse = RecordingTransport.fail("Provider HTTP 404: endpoint unsupported");
        RecordingTransport chat = RecordingTransport.events(
            "{\"id\":\"chatcmpl-1\",\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}",
            "[DONE]"
        );
        OpenAiCompatibleProviderAdapter adapter = new OpenAiCompatibleProviderAdapter(
            config(TransportMode.AUTO, "test-key"),
            websocket,
            sse,
            chat
        );

        List<AssistantStreamEvent> events = collect(adapter.stream(context(), descriptor(), () -> false));

        assertThat(websocket.requests).hasSize(1);
        assertThat(sse.requests).hasSize(1);
        assertThat(chat.requests).hasSize(1);
        assertThat(websocket.requests.getFirst().uri().getScheme()).isEqualTo("wss");
        JsonNode webSocketBody = OBJECT_MAPPER.readTree(websocket.requests.getFirst().body());
        assertThat(webSocketBody.get("type").asText()).isEqualTo("response.create");
        assertThat(webSocketBody.get("stream")).isNull();
        assertThat(webSocketBody.at("/response/model").asText()).isEqualTo("gpt-5-mini");
        assertThat(sse.requests.getFirst().uri().getPath()).isEqualTo("/v1/responses");
        assertThat(chat.requests.getFirst().uri().getPath()).isEqualTo("/v1/chat/completions");
        assertThat(events).contains(new TextDelta("hello"), new AssistantDone(Optional.empty(), Optional.of("stop")));
    }

    @Test
    void includesRuntimeToolSnapshotInResponsesRequest() throws Exception {
        RecordingTransport websocket = RecordingTransport.events(
            "{\"type\":\"response.created\",\"response\":{\"id\":\"resp-1\"}}",
            "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp-1\"}}"
        );
        OpenAiCompatibleProviderAdapter adapter = new OpenAiCompatibleProviderAdapter(
            config(TransportMode.WEBSOCKET, "test-key"),
            websocket,
            RecordingTransport.events(),
            RecordingTransport.events()
        );
        ToolRegistrySnapshot tools = new ToolRegistrySnapshot(List.of(new ToolDescriptor(
            "read",
            List.of("cat"),
            "读取文件内容。",
            new JsonSchema(Map.of(
                "type", "object",
                "properties", Map.of("path", Map.of("type", "string")),
                "required", List.of("path")
            )),
            true,
            false
        )));

        collect(adapter.stream(context(), descriptor(), tools, () -> false));

        JsonNode tool = OBJECT_MAPPER.readTree(websocket.requests.getFirst().body()).at("/response/tools/0");
        assertThat(tool.get("type").asText()).isEqualTo("function");
        assertThat(tool.get("name").asText()).isEqualTo("read");
        assertThat(tool.get("description").asText()).isEqualTo("读取文件内容。");
        assertThat(tool.at("/parameters/properties/path/type").asText()).isEqualTo("string");
    }

    @Test
    void includesRuntimeToolSnapshotInChatCompletionsFallbackRequest() throws Exception {
        RecordingTransport websocket = RecordingTransport.fail("WebSocket handshake failed");
        RecordingTransport sse = RecordingTransport.fail("Provider HTTP 404: endpoint unsupported");
        RecordingTransport chat = RecordingTransport.events(
            "{\"id\":\"chatcmpl-1\",\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}",
            "[DONE]"
        );
        OpenAiCompatibleProviderAdapter adapter = new OpenAiCompatibleProviderAdapter(
            config(TransportMode.AUTO, "test-key"),
            websocket,
            sse,
            chat
        );
        ToolRegistrySnapshot tools = new ToolRegistrySnapshot(List.of(new ToolDescriptor(
            "read",
            List.of("cat"),
            "读取文件内容。",
            new JsonSchema(Map.of(
                "type", "object",
                "properties", Map.of("path", Map.of("type", "string")),
                "required", List.of("path")
            )),
            true,
            false
        )));

        collect(adapter.stream(context(), descriptor(), tools, () -> false));

        JsonNode tool = OBJECT_MAPPER.readTree(chat.requests.getFirst().body()).at("/tools/0");
        assertThat(tool.get("type").asText()).isEqualTo("function");
        assertThat(tool.at("/function/name").asText()).isEqualTo("read");
        assertThat(tool.at("/function/description").asText()).isEqualTo("读取文件内容。");
        assertThat(tool.at("/function/parameters/properties/path/type").asText()).isEqualTo("string");
    }

    @Test
    void mapsStreamSessionIdToPromptCacheKeyAcrossOpenAiRequests() throws Exception {
        RecordingTransport websocket = RecordingTransport.fail("WebSocket handshake failed");
        RecordingTransport sse = RecordingTransport.fail("Provider HTTP 404: endpoint unsupported");
        RecordingTransport chat = RecordingTransport.events(
            "{\"id\":\"chatcmpl-1\",\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}",
            "[DONE]"
        );
        OpenAiCompatibleProviderAdapter adapter = new OpenAiCompatibleProviderAdapter(
            config(TransportMode.AUTO, "test-key"),
            websocket,
            sse,
            chat
        );

        collect(adapter.stream(context(), descriptor(), AiProviderRuntimePort.emptyTools(), new AiStreamOptions("ses_main"), () -> false));

        assertThat(OBJECT_MAPPER.readTree(websocket.requests.getFirst().body()).at("/response/prompt_cache_key").asText())
            .isEqualTo("ses_main");
        assertThat(OBJECT_MAPPER.readTree(sse.requests.getFirst().body()).get("prompt_cache_key").asText())
            .isEqualTo("ses_main");
        assertThat(OBJECT_MAPPER.readTree(chat.requests.getFirst().body()).get("prompt_cache_key").asText())
            .isEqualTo("ses_main");
    }

    @Test
    void doesNotOpenTransportUntilStreamIsConsumed() {
        RecordingTransport websocket = RecordingTransport.events("{\"type\":\"response.created\",\"response\":{\"id\":\"resp-1\"}}");
        OpenAiCompatibleProviderAdapter adapter = new OpenAiCompatibleProviderAdapter(
            config(TransportMode.WEBSOCKET, "test-key"),
            websocket,
            RecordingTransport.events(),
            RecordingTransport.events()
        );

        try (var ignored = adapter.stream(context(), descriptor(), () -> false)) {
            assertThat(websocket.requests).isEmpty();
        }
    }

    @Test
    void aggregatesResultAfterCompleteConsumption() {
        RecordingTransport websocket = RecordingTransport.events(
            "{\"type\":\"response.created\",\"response\":{\"id\":\"resp-1\"}}",
            "{\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}",
            "{\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":1,\"output_tokens\":2}}}"
        );
        OpenAiCompatibleProviderAdapter adapter = new OpenAiCompatibleProviderAdapter(
            config(TransportMode.WEBSOCKET, "test-key"),
            websocket,
            RecordingTransport.events(),
            RecordingTransport.events()
        );

        try (var stream = adapter.stream(context(), descriptor(), () -> false)) {
            List<AssistantStreamEvent> events = StreamSupport.stream(stream.spliterator(), false).toList();

            assertThat(events).contains(new TextDelta("hello"), new AssistantDone(Optional.of(new cn.lypi.contracts.model.TokenUsage(1, 2, 0, 0)), Optional.of("stop")));
            assertThat(stream.result().messageId()).isEqualTo("resp-1");
            assertThat(stream.result().events()).containsExactlyElementsOf(events);
            assertThat(stream.result().completed()).isTrue();
            assertThat(stream.result().aborted()).isFalse();
            assertThat(stream.result().stopReason()).contains("stop");
        }
    }

    @Test
    void preservesResponsesConversationStateAfterCompleteConsumption() {
        RecordingTransport websocket = RecordingTransport.events(
            "{\"type\":\"response.created\",\"response\":{\"id\":\"resp-1\"}}",
            "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp-1\"}}"
        );
        OpenAiCompatibleProviderAdapter adapter = new OpenAiCompatibleProviderAdapter(
            config(TransportMode.WEBSOCKET, "test-key"),
            websocket,
            RecordingTransport.events(),
            RecordingTransport.events()
        );

        try (var stream = adapter.stream(context(), descriptor(), () -> false)) {
            StreamSupport.stream(stream.spliterator(), false).toList();

            assertThat(stream.result().providerConversationState())
                .hasValueSatisfying(state -> {
                    assertThat(state.provider()).isEqualTo("openai");
                    assertThat(state.style()).isEqualTo("responses");
                    assertThat(state.previousResponseId()).contains("resp-1");
                });
        }
    }

    @Test
    void emitsThinkingDeltaFromReasoningOutputItemDone() {
        RecordingTransport websocket = RecordingTransport.events(
            "{\"type\":\"response.created\",\"response\":{\"id\":\"resp-1\"}}",
            "{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"type\":\"reasoning\",\"summary\":[{\"type\":\"summary_text\",\"text\":\"reasoned\"}]}}",
            "{\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}",
            "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp-1\"}}"
        );
        OpenAiCompatibleProviderAdapter adapter = new OpenAiCompatibleProviderAdapter(
            config(TransportMode.WEBSOCKET, "test-key"),
            websocket,
            RecordingTransport.events(),
            RecordingTransport.events()
        );

        List<AssistantStreamEvent> events = collect(adapter.stream(context(), descriptor(), () -> false));

        assertThat(events).contains(new ThinkingDelta("reasoned"), new TextDelta("hello"));
    }

    @Test
    void marksResultAbortedWhenClosedBeforeCompletion() {
        RecordingTransport websocket = RecordingTransport.events(
            "{\"type\":\"response.created\",\"response\":{\"id\":\"resp-1\"}}",
            "{\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}"
        );
        OpenAiCompatibleProviderAdapter adapter = new OpenAiCompatibleProviderAdapter(
            config(TransportMode.WEBSOCKET, "test-key"),
            websocket,
            RecordingTransport.events(),
            RecordingTransport.events()
        );

        var stream = adapter.stream(context(), descriptor(), () -> false);
        Iterator<AssistantStreamEvent> iterator = stream.iterator();
        assertThat(iterator.hasNext()).isTrue();
        assertThat(iterator.next()).isInstanceOf(cn.lypi.contracts.model.AssistantStart.class);
        stream.close();

        assertThat(stream.result().aborted()).isTrue();
        assertThat(stream.result().completed()).isFalse();
        assertThat(stream.result().messageId()).isEqualTo("resp-1");
    }

    @Test
    void closeAfterSignalAbortMarksResultAborted() {
        RecordingTransport websocket = RecordingTransport.events(
            "{\"type\":\"response.created\",\"response\":{\"id\":\"resp-1\"}}"
        );
        MutableAbortSignal signal = new MutableAbortSignal();
        OpenAiCompatibleProviderAdapter adapter = new OpenAiCompatibleProviderAdapter(
            config(TransportMode.WEBSOCKET, "test-key"),
            websocket,
            RecordingTransport.events(),
            RecordingTransport.events()
        );

        var stream = adapter.stream(context(), descriptor(), signal);
        Iterator<AssistantStreamEvent> iterator = stream.iterator();
        assertThat(iterator.hasNext()).isTrue();
        assertThat(iterator.next()).isInstanceOf(cn.lypi.contracts.model.AssistantStart.class);
        signal.abort();
        stream.close();

        assertThat(stream.result().aborted()).isTrue();
        assertThat(stream.result().completed()).isFalse();
    }

    @Test
    @Timeout(2)
    void abortDuringProviderReadStopsIterationWithoutReopeningAttempt() {
        MutableAbortSignal signal = new MutableAbortSignal();
        AbortOnReadTransport websocket = new AbortOnReadTransport(signal);
        OpenAiCompatibleProviderAdapter adapter = new OpenAiCompatibleProviderAdapter(
            config(TransportMode.WEBSOCKET, "test-key"),
            websocket,
            RecordingTransport.events(),
            RecordingTransport.events()
        );

        try (var stream = adapter.stream(context(), descriptor(), signal)) {
            Iterator<AssistantStreamEvent> iterator = stream.iterator();

            assertThat(iterator.hasNext()).isFalse();
            assertThat(stream.result().aborted()).isTrue();
            assertThat(stream.result().completed()).isFalse();
        }
        assertThat(websocket.requests).hasSize(1);
    }

    @Test
    void fallsBackWhenAttemptClosesBeforeAnyAssistantDoneOrOutput() {
        RecordingTransport websocket = RecordingTransport.events();
        RecordingTransport sse = RecordingTransport.events();
        RecordingTransport chat = RecordingTransport.events(
            "{\"id\":\"chatcmpl-1\",\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}",
            "[DONE]"
        );
        OpenAiCompatibleProviderAdapter adapter = new OpenAiCompatibleProviderAdapter(
            config(TransportMode.AUTO, "test-key"),
            websocket,
            sse,
            chat
        );

        List<AssistantStreamEvent> events = collect(adapter.stream(context(), descriptor(), () -> false));

        assertThat(websocket.requests).hasSize(1);
        assertThat(sse.requests).hasSize(1);
        assertThat(chat.requests).hasSize(1);
        assertThat(events).contains(new TextDelta("hello"), new AssistantDone(Optional.empty(), Optional.of("stop")));
    }

    @Test
    void reportsErrorWhenAttemptClosesAfterOutputWithoutAssistantDone() {
        RecordingTransport websocket = RecordingTransport.events(
            "{\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}"
        );
        RecordingTransport sse = RecordingTransport.events();
        RecordingTransport chat = RecordingTransport.events();
        OpenAiCompatibleProviderAdapter adapter = new OpenAiCompatibleProviderAdapter(
            config(TransportMode.WEBSOCKET, "test-key"),
            websocket,
            sse,
            chat
        );

        try (var stream = adapter.stream(context(), descriptor(), () -> false)) {
            Iterator<AssistantStreamEvent> iterator = stream.iterator();

            assertThat(iterator.hasNext()).isTrue();
            assertThat(iterator.next()).isEqualTo(new TextDelta("hello"));
            assertThatThrownBy(iterator::hasNext)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("completed without AssistantDone");
            assertThat(stream.result().completed()).isFalse();
            assertThat(stream.result().error()).isPresent();
        }
        assertThat(sse.requests).isEmpty();
        assertThat(chat.requests).isEmpty();
    }

    @Test
    void doesNotFallbackAfterAnyOutputStarted() {
        RecordingTransport websocket = RecordingTransport.eventsThenFail(
            "Provider stream failed after output",
            "{\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}"
        );
        RecordingTransport sse = RecordingTransport.events();
        RecordingTransport chat = RecordingTransport.events();
        OpenAiCompatibleProviderAdapter adapter = new OpenAiCompatibleProviderAdapter(
            config(TransportMode.WEBSOCKET, "test-key"),
            websocket,
            sse,
            chat
        );

        try (var stream = adapter.stream(context(), descriptor(), () -> false)) {
            Iterator<AssistantStreamEvent> iterator = stream.iterator();

            assertThat(iterator.hasNext()).isTrue();
            assertThat(iterator.next()).isEqualTo(new TextDelta("hello"));
            assertThatThrownBy(iterator::hasNext)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Provider stream failed after output");
            assertThat(stream.result().events()).containsExactly(new TextDelta("hello"));
            assertThat(stream.result().events()).noneMatch(ProviderRetryNotice.class::isInstance);
            assertThat(stream.result().error()).isPresent();
            assertThat(stream.result().completed()).isFalse();
        }
        assertThat(sse.requests).isEmpty();
        assertThat(chat.requests).isEmpty();
    }

    @Test
    void failsClearlyWhenApiKeyIsMissing() {
        OpenAiCompatibleProviderAdapter adapter = new OpenAiCompatibleProviderAdapter(
            config(TransportMode.AUTO, ""),
            RecordingTransport.events(),
            RecordingTransport.events(),
            RecordingTransport.events()
        );

        assertThatThrownBy(() -> collect(adapter.stream(context(), descriptor(), () -> false)))
            .isInstanceOfSatisfying(ModelProviderException.class, error -> {
                assertThat(error.errorId()).isEqualTo("provider.api_key_missing");
                assertThat(error.getMessage()).doesNotContain("Bearer");
            });
    }

    @Test
    void respectsChatCompletionsAsPrimaryRequestStyle() {
        RecordingTransport websocket = RecordingTransport.events();
        RecordingTransport sse = RecordingTransport.events();
        RecordingTransport chat = RecordingTransport.events(
            "{\"id\":\"chatcmpl-1\",\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}",
            "[DONE]"
        );
        OpenAiCompatibleProviderAdapter adapter = new OpenAiCompatibleProviderAdapter(
            config(TransportMode.AUTO, "test-key", RequestStyle.CHAT_COMPLETIONS, RequestStyle.RESPONSES),
            websocket,
            sse,
            chat
        );

        List<AssistantStreamEvent> events = collect(adapter.stream(context(), descriptor(), () -> false));

        assertThat(websocket.requests).isEmpty();
        assertThat(sse.requests).isEmpty();
        assertThat(chat.requests).hasSize(1);
        assertThat(chat.requests.getFirst().uri().getPath()).isEqualTo("/v1/chat/completions");
        assertThat(events).contains(new TextDelta("hello"));
    }

    @Test
    void retriesAttemptAccordingToConfiguredMaxRetriesBeforeFallback() {
        RecordingTransport websocket = RecordingTransport.fail("WebSocket handshake failed");
        RecordingTransport sse = RecordingTransport.events();
        RecordingTransport chat = RecordingTransport.events();
        OpenAiCompatibleProviderAdapter adapter = new OpenAiCompatibleProviderAdapter(
            config(TransportMode.WEBSOCKET, "test-key", RequestStyle.RESPONSES, RequestStyle.CHAT_COMPLETIONS, 2),
            websocket,
            sse,
            chat
        );

        List<AssistantStreamEvent> events = collect(adapter.stream(context(), descriptor(), () -> false));

        assertThat(websocket.requests).hasSize(3);
        assertThat(events)
            .filteredOn(ProviderRetryNotice.class::isInstance)
            .containsExactly(
                new ProviderRetryNotice(
                    "openai",
                    1,
                    2,
                    Duration.ofMillis(500),
                    "fallback_candidate",
                    "provider.fallback_candidate",
                    "WebSocket handshake failed"
                ),
                new ProviderRetryNotice(
                    "openai",
                    2,
                    2,
                    Duration.ofMillis(1_000),
                    "fallback_candidate",
                    "provider.fallback_candidate",
                    "WebSocket handshake failed"
                )
            );
        assertThat(events)
            .filteredOn(cn.lypi.contracts.model.AssistantError.class::isInstance)
            .singleElement()
            .isInstanceOf(cn.lypi.contracts.model.AssistantError.class);
    }

    @Test
    void emitsRetryNoticeBeforeOpeningNextProviderAttempt() {
        RecordingTransport websocket = RecordingTransport.fail("Provider HTTP 429: rate limit");
        OpenAiCompatibleProviderAdapter adapter = new OpenAiCompatibleProviderAdapter(
            config(TransportMode.WEBSOCKET, "test-key", RequestStyle.RESPONSES, RequestStyle.CHAT_COMPLETIONS, 1),
            websocket,
            RecordingTransport.events(),
            RecordingTransport.events()
        );

        try (AssistantEventStream stream = adapter.stream(context(), descriptor(), () -> false)) {
            Iterator<AssistantStreamEvent> iterator = stream.iterator();

            assertThat(iterator.hasNext()).isTrue();
            assertThat(websocket.requests).hasSize(1);
            assertThat(iterator.next()).isInstanceOf(ProviderRetryNotice.class);
            assertThat(websocket.requests).hasSize(1);
        }
    }

    @Test
    void doesNotRetryAuthenticationFailures() {
        RecordingTransport websocket = RecordingTransport.fail("Provider HTTP 401: unauthorized");
        OpenAiCompatibleProviderAdapter adapter = new OpenAiCompatibleProviderAdapter(
            config(TransportMode.WEBSOCKET, "test-key", RequestStyle.RESPONSES, RequestStyle.CHAT_COMPLETIONS, 2),
            websocket,
            RecordingTransport.events(),
            RecordingTransport.events()
        );

        List<AssistantStreamEvent> events = collect(adapter.stream(context(), descriptor(), () -> false));

        assertThat(websocket.requests).hasSize(1);
        assertThat(events).noneMatch(ProviderRetryNotice.class::isInstance);
        assertThat(events).singleElement().isInstanceOf(cn.lypi.contracts.model.AssistantError.class);
    }

    @Test
    void disablesPreviousResponseIdAfterProviderReportsWebSocketV2Only() throws Exception {
        RecordingTransport websocket = RecordingTransport.events();
        RecordingTransport sse = RecordingTransport.fail(
            "Provider HTTP 400: previous_response_id is only supported on Responses WebSocket v2"
        );
        RecordingTransport chat = RecordingTransport.events(
            "{\"id\":\"chatcmpl-1\",\"choices\":[{\"delta\":{\"content\":\"fallback\"}}]}",
            "[DONE]"
        );
        OpenAiCompatibleProviderAdapter adapter = new OpenAiCompatibleProviderAdapter(
            config(TransportMode.SSE, "test-key", RequestStyle.RESPONSES, RequestStyle.CHAT_COMPLETIONS),
            websocket,
            sse,
            chat
        );
        AiStreamOptions options = new AiStreamOptions("ses_main");

        collect(adapter.stream(contextWithProviderConversationState(), descriptor(), AiProviderRuntimePort.emptyTools(), options, () -> false));
        collect(adapter.stream(contextWithProviderConversationState(), descriptor(), AiProviderRuntimePort.emptyTools(), options, () -> false));

        assertThat(sse.requests).hasSize(2);
        JsonNode firstRequest = OBJECT_MAPPER.readTree(sse.requests.get(0).body());
        JsonNode secondRequest = OBJECT_MAPPER.readTree(sse.requests.get(1).body());
        assertThat(firstRequest.get("previous_response_id").asText()).isEqualTo("resp-123");
        assertThat(firstRequest.get("input")).hasSize(1);
        assertThat(secondRequest.get("previous_response_id")).isNull();
        assertThat(secondRequest.get("prompt_cache_key").asText()).isEqualTo("ses_main");
        assertThat(secondRequest.get("input")).hasSize(3);
        assertThat(secondRequest.at("/input/0/content/0/text").asText()).isEqualTo("first");
        assertThat(secondRequest.at("/input/2/content/0/text").asText()).isEqualTo("follow up");
    }

    private static OpenAiProviderConfig config(TransportMode transportMode, String apiKey) {
        return config(transportMode, apiKey, RequestStyle.RESPONSES, RequestStyle.CHAT_COMPLETIONS);
    }

    private static List<AssistantStreamEvent> collect(AssistantEventStream stream) {
        try (stream) {
            return StreamSupport.stream(stream.spliterator(), false).toList();
        }
    }

    private static OpenAiProviderConfig config(
        TransportMode transportMode,
        String apiKey,
        RequestStyle requestStyle,
        RequestStyle fallbackRequestStyle
    ) {
        return config(transportMode, apiKey, requestStyle, fallbackRequestStyle, 0);
    }

    private static OpenAiProviderConfig config(
        TransportMode transportMode,
        String apiKey,
        RequestStyle requestStyle,
        RequestStyle fallbackRequestStyle,
        int maxRetries
    ) {
        return new OpenAiProviderConfig(
            "openai",
            URI.create("https://api.openai.test/v1"),
            Optional.empty(),
            "/v1/responses",
            apiKey,
            requestStyle,
            fallbackRequestStyle,
            transportMode,
            Duration.ofSeconds(30),
            maxRetries,
            Map.of()
        );
    }

    private static ModelDescriptor descriptor() {
        return new ModelDescriptor(
            "openai",
            "gpt-5-mini",
            URI.create("https://api.openai.test/v1"),
            cn.lypi.contracts.model.ApiStyle.OPENAI_COMPATIBLE,
            128_000,
            16_384,
            true,
            false,
            new cn.lypi.contracts.model.CostProfile(BigDecimal.ZERO, BigDecimal.ZERO, "USD"),
            Map.of()
        );
    }

    private static ContextSnapshot context() {
        return new ContextSnapshot(
            new SystemPrompt("system", List.of("test"), "hash"),
            List.of(new AgentMessage(
                "msg-1",
                MessageRole.USER,
                MessageKind.TEXT,
                List.of(new TextContentBlock("hello")),
                Instant.EPOCH,
                Optional.empty(),
                Optional.empty()
            )),
            new ModelSelection("openai", "gpt-5-mini", ThinkingLevel.HIGH),
            ThinkingLevel.HIGH,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE,
            new ContextBudget(0, 128_000, 100_000, 16_384, 8_192, 0, 0, BigDecimal.ZERO)
        );
    }

    private static ContextSnapshot contextWithProviderConversationState() {
        return new ContextSnapshot(
            new SystemPrompt("system", List.of("test"), "hash"),
            List.of(
                new AgentMessage(
                    "msg-user-1",
                    MessageRole.USER,
                    MessageKind.TEXT,
                    List.of(new TextContentBlock("first")),
                    Instant.EPOCH,
                    Optional.empty(),
                    Optional.empty()
                ),
                new AgentMessage(
                    "msg-assistant-1",
                    MessageRole.ASSISTANT,
                    MessageKind.TEXT,
                    List.of(new TextContentBlock(
                        "answer",
                        Map.of("providerConversationState", Map.of(
                            "provider", "openai",
                            "style", "responses",
                            "previousResponseId", "resp-123"
                        ))
                    )),
                    Instant.EPOCH,
                    Optional.empty(),
                    Optional.empty()
                ),
                new AgentMessage(
                    "msg-user-2",
                    MessageRole.USER,
                    MessageKind.TEXT,
                    List.of(new TextContentBlock("follow up")),
                    Instant.EPOCH,
                    Optional.empty(),
                    Optional.empty()
                )
            ),
            new ModelSelection("openai", "gpt-5-mini", ThinkingLevel.HIGH),
            ThinkingLevel.HIGH,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE,
            new ContextBudget(0, 128_000, 100_000, 16_384, 8_192, 0, 0, BigDecimal.ZERO)
        );
    }

    private static final class RecordingTransport implements ProviderTransport {
        private final List<String> events;
        private final RuntimeException failure;
        private final List<ProviderRequest> requests = new ArrayList<>();

        private RecordingTransport(List<String> events, RuntimeException failure) {
            this.events = events;
            this.failure = failure;
        }

        private static RecordingTransport events(String... events) {
            return new RecordingTransport(List.of(events), null);
        }

        private static RecordingTransport fail(String message) {
            return new RecordingTransport(List.of(), new IllegalStateException(message));
        }

        private static RecordingTransport eventsThenFail(String message, String... events) {
            return new RecordingTransport(List.of(events), new IllegalStateException(message));
        }

        @Override
        public ProviderEventStream stream(ProviderRequest request, AbortSignal signal) {
            requests.add(request);
            if (failure != null && events.isEmpty()) {
                throw failure;
            }
            if (failure != null) {
                return new FailingProviderEventStream(events, failure);
            }
            return new ListProviderEventStream(events.stream().map(ProviderRawEvent::new).toList());
        }
    }

    private static final class FailingProviderEventStream implements ProviderEventStream {
        private final List<String> events;
        private final RuntimeException failure;

        private FailingProviderEventStream(List<String> events, RuntimeException failure) {
            this.events = events;
            this.failure = failure;
        }

        @Override
        public Iterator<ProviderRawEvent> iterator() {
            return new Iterator<>() {
                private int index;

                @Override
                public boolean hasNext() {
                    if (index < events.size()) {
                        return true;
                    }
                    throw failure;
                }

                @Override
                public ProviderRawEvent next() {
                    if (index >= events.size()) {
                        throw new NoSuchElementException();
                    }
                    return new ProviderRawEvent(events.get(index++));
                }
            };
        }

        @Override
        public void close() {
        }
    }

    private static final class MutableAbortSignal implements AbortSignal {
        private boolean aborted;

        @Override
        public boolean aborted() {
            return aborted;
        }

        private void abort() {
            aborted = true;
        }
    }

    private static final class AbortOnReadTransport implements ProviderTransport {
        private final MutableAbortSignal signal;
        private final List<ProviderRequest> requests = new ArrayList<>();

        private AbortOnReadTransport(MutableAbortSignal signal) {
            this.signal = signal;
        }

        @Override
        public ProviderEventStream stream(ProviderRequest request, AbortSignal ignored) {
            requests.add(request);
            if (requests.size() > 1) {
                throw new IllegalStateException("stream reopened after abort");
            }
            return new ProviderEventStream() {
                @Override
                public Iterator<ProviderRawEvent> iterator() {
                    return new Iterator<>() {
                        @Override
                        public boolean hasNext() {
                            signal.abort();
                            return false;
                        }

                        @Override
                        public ProviderRawEvent next() {
                            throw new NoSuchElementException();
                        }
                    };
                }

                @Override
                public void close() {
                }
            };
        }
    }
}
