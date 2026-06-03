package cn.lypi.ai.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.junit.jupiter.api.Test;

class OpenAiCompatibleProviderAdapterTest {
    @Test
    void triesWebSocketResponsesThenSseResponsesThenChatCompletionsFallback() {
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

        List<AssistantStreamEvent> events = adapter.stream(context(), descriptor(), () -> false).toList();

        assertThat(websocket.requests).hasSize(1);
        assertThat(sse.requests).hasSize(1);
        assertThat(chat.requests).hasSize(1);
        assertThat(websocket.requests.getFirst().uri().getScheme()).isEqualTo("wss");
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

        assertThat(adapter.stream(context(), descriptor(), () -> false).toList())
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

        assertThatThrownBy(() -> adapter.stream(context(), descriptor(), () -> false).toList())
            .isInstanceOfSatisfying(ModelProviderException.class, error -> {
                assertThat(error.errorId()).isEqualTo("provider.api_key_missing");
                assertThat(error.getMessage()).doesNotContain("Bearer");
            });
    }

    private static OpenAiProviderConfig config(TransportMode transportMode, String apiKey) {
        return new OpenAiProviderConfig(
            "openai",
            URI.create("https://api.openai.test/v1"),
            Optional.empty(),
            "/v1/responses",
            apiKey,
            RequestStyle.RESPONSES,
            RequestStyle.CHAT_COMPLETIONS,
            transportMode,
            Duration.ofSeconds(30),
            1,
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
        public Stream<ProviderRawEvent> stream(ProviderRequest request, AbortSignal signal) {
            requests.add(request);
            if (failure != null) {
                throw failure;
            }
            return events.stream().map(ProviderRawEvent::new);
        }
    }
}
