package cn.lypi.ai.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lypi.ai.provider.ProviderRawEvent;
import cn.lypi.ai.provider.ProviderRequest;
import cn.lypi.ai.provider.ProviderEventStream;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class WebSocketProviderTransportTest {
    @Test
    void derivesWebSocketUriFromBaseUrlAndPath() {
        assertThat(WebSocketProviderTransport.deriveUri(URI.create("https://api.example.test/v1"), "/v1/responses"))
            .isEqualTo(URI.create("wss://api.example.test/v1/responses"));
        assertThat(WebSocketProviderTransport.deriveUri(URI.create("http://localhost:8080/v1"), "/responses"))
            .isEqualTo(URI.create("ws://localhost:8080/responses"));
    }

    @Test
    void sendsPayloadAndReturnsReceivedMessages() {
        RecordingWebSocketClient client = new RecordingWebSocketClient(List.of("{\"type\":\"response.created\"}", "[DONE]"));
        WebSocketProviderTransport transport = new WebSocketProviderTransport(client);
        ProviderRequest request = new ProviderRequest(
            URI.create("wss://api.example.test/v1/responses"),
            Map.of("Authorization", "Bearer ${LYPI_TEST_TOKEN}"),
            "{\"stream\":true}",
            Optional.of(Duration.ofSeconds(7))
        );

        List<ProviderRawEvent> events;
        try (ProviderEventStream stream = transport.stream(request, () -> false)) {
            events = StreamSupport.stream(stream.spliterator(), false).toList();
        }

        assertThat(client.uri).isEqualTo(request.uri());
        assertThat(client.headers).containsEntry("Authorization", "Bearer ${LYPI_TEST_TOKEN}");
        assertThat(client.payload).isEqualTo("{\"stream\":true}");
        assertThat(client.timeout).isEqualTo(Duration.ofSeconds(7));
        assertThat(events).containsExactly(new ProviderRawEvent("{\"type\":\"response.created\"}"), new ProviderRawEvent("[DONE]"));
    }

    @Test
    void returnsEmptyWhenAlreadyAborted() {
        RecordingWebSocketClient client = new RecordingWebSocketClient(List.of("ignored"));
        WebSocketProviderTransport transport = new WebSocketProviderTransport(client);
        ProviderRequest request = new ProviderRequest(URI.create("wss://api.example.test/v1/responses"), Map.of(), "{}");

        try (ProviderEventStream stream = transport.stream(request, () -> true)) {
            assertThat(StreamSupport.stream(stream.spliterator(), false).toList()).isEmpty();
        }
        assertThat(client.payload).isNull();
    }

    @Test
    void closeNotifiesClientStream() {
        RecordingWebSocketClient client = new RecordingWebSocketClient(List.of("{\"type\":\"response.created\"}"));
        WebSocketProviderTransport transport = new WebSocketProviderTransport(client);
        ProviderRequest request = new ProviderRequest(URI.create("wss://api.example.test/v1/responses"), Map.of(), "{}");

        ProviderEventStream stream = transport.stream(request, () -> false);
        stream.close();

        assertThat(client.stream.closed).isTrue();
    }

    @Test
    void jdkClientFailsWhenProviderDoesNotCompleteBeforeTimeout() {
        WebSocketProviderTransport.CollectorListener listener = new WebSocketProviderTransport.CollectorListener();

        assertThatThrownBy(() -> listener.await(Duration.ofMillis(1)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Provider WebSocket request timed out.");
    }

    @Test
    void jdkClientFailsWhenProviderReportsWebSocketError() {
        WebSocketProviderTransport.CollectorListener listener = new WebSocketProviderTransport.CollectorListener();
        RuntimeException providerError = new RuntimeException("connection refused");

        listener.onError(null, providerError);

        assertThatThrownBy(() -> listener.await(Duration.ofSeconds(1)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Provider WebSocket request failed.")
            .hasCause(providerError);
    }

    private static final class RecordingWebSocketClient implements WebSocketProviderTransport.WebSocketClient {
        private final List<String> messages;
        private URI uri;
        private Map<String, String> headers;
        private String payload;
        private Duration timeout;

        private RecordingWebSocketClient(List<String> messages) {
            this.messages = messages;
        }

        private RecordingProviderEventStream stream;

        @Override
        public ProviderEventStream open(URI uri, Map<String, String> headers, String payload, Duration timeout, cn.lypi.contracts.common.AbortSignal signal) {
            this.uri = uri;
            this.headers = headers;
            this.payload = payload;
            this.timeout = timeout;
            this.stream = new RecordingProviderEventStream(messages);
            return stream;
        }
    }

    private static final class RecordingProviderEventStream implements ProviderEventStream {
        private final List<String> messages;
        private boolean closed;

        private RecordingProviderEventStream(List<String> messages) {
            this.messages = messages;
        }

        @Override
        public java.util.Iterator<ProviderRawEvent> iterator() {
            return messages.stream().map(ProviderRawEvent::new).iterator();
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
