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
import cn.lypi.contracts.model.TextDelta;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
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
    void doesNotFallbackAfterAnyOutputStarted() {
        RecordingTransport websocket = RecordingTransport.events("{\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}");
        RecordingTransport sse = RecordingTransport.events();
        RecordingTransport chat = RecordingTransport.events();
        OpenAiCompatibleProviderAdapter adapter = new OpenAiCompatibleProviderAdapter(
            config(TransportMode.WEBSOCKET, "test-key"),
            websocket,
            sse,
            chat
        );

        assertThat(collect(adapter.stream(context(), descriptor(), () -> false)))
            .containsExactly(new TextDelta("hello"));
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
        assertThat(events).singleElement().isInstanceOf(cn.lypi.contracts.model.AssistantError.class);
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

        @Override
        public ProviderEventStream stream(ProviderRequest request, AbortSignal signal) {
            requests.add(request);
            if (failure != null) {
                throw failure;
            }
            return new ListProviderEventStream(events.stream().map(ProviderRawEvent::new).toList());
        }
    }
}
