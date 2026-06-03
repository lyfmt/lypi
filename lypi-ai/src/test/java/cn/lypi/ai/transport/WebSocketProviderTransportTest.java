package cn.lypi.ai.transport;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.ai.provider.ProviderRawEvent;
import cn.lypi.ai.provider.ProviderRequest;
import java.net.URI;
import java.util.List;
import java.util.Map;
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
            Map.of("Authorization", "Bearer test-secret"),
            "{\"stream\":true}"
        );

        List<ProviderRawEvent> events = transport.stream(request, () -> false).toList();

        assertThat(client.uri).isEqualTo(request.uri());
        assertThat(client.headers).containsEntry("Authorization", "Bearer test-secret");
        assertThat(client.payload).isEqualTo("{\"stream\":true}");
        assertThat(events).containsExactly(new ProviderRawEvent("{\"type\":\"response.created\"}"), new ProviderRawEvent("[DONE]"));
    }

    @Test
    void returnsEmptyWhenAlreadyAborted() {
        RecordingWebSocketClient client = new RecordingWebSocketClient(List.of("ignored"));
        WebSocketProviderTransport transport = new WebSocketProviderTransport(client);
        ProviderRequest request = new ProviderRequest(URI.create("wss://api.example.test/v1/responses"), Map.of(), "{}");

        assertThat(transport.stream(request, () -> true)).isEmpty();
        assertThat(client.payload).isNull();
    }

    private static final class RecordingWebSocketClient implements WebSocketProviderTransport.WebSocketClient {
        private final List<String> messages;
        private URI uri;
        private Map<String, String> headers;
        private String payload;

        private RecordingWebSocketClient(List<String> messages) {
            this.messages = messages;
        }

        @Override
        public List<String> exchange(URI uri, Map<String, String> headers, String payload) {
            this.uri = uri;
            this.headers = headers;
            this.payload = payload;
            return messages;
        }
    }
}
