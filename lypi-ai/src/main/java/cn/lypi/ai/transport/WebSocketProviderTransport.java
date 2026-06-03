package cn.lypi.ai.transport;

import cn.lypi.ai.provider.ProviderRawEvent;
import cn.lypi.ai.provider.ProviderRequest;
import cn.lypi.ai.provider.ProviderTransport;
import cn.lypi.contracts.common.AbortSignal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public final class WebSocketProviderTransport implements ProviderTransport {
    private final WebSocketClient client;

    public WebSocketProviderTransport() {
        this(new JdkWebSocketClient(HttpClient.newHttpClient()));
    }

    public WebSocketProviderTransport(WebSocketClient client) {
        this.client = client;
    }

    @Override
    public Stream<ProviderRawEvent> stream(ProviderRequest request, AbortSignal signal) {
        if (signal.aborted()) {
            return Stream.empty();
        }
        Duration timeout = request.timeout().orElse(Duration.ofSeconds(30));
        return client.exchange(request.uri(), request.headers(), request.body(), timeout).stream()
            .map(ProviderRawEvent::new);
    }

    /**
     * 从 HTTP base URL 推导 WebSocket URL。
     *
     * base URL 的路径被 `websocketPath` 替换，符合当前 provider 配置约定。
     */
    public static URI deriveUri(URI baseUrl, String websocketPath) {
        String scheme = switch (baseUrl.getScheme()) {
            case "https" -> "wss";
            case "http" -> "ws";
            default -> throw new IllegalArgumentException("Unsupported base URL scheme: " + baseUrl.getScheme());
        };
        String path = websocketPath.startsWith("/") ? websocketPath : "/" + websocketPath;
        return URI.create(scheme + "://" + baseUrl.getAuthority() + path);
    }

    public interface WebSocketClient {
        List<String> exchange(URI uri, Map<String, String> headers, String payload, Duration timeout);
    }

    private static final class JdkWebSocketClient implements WebSocketClient {
        private final HttpClient httpClient;

        private JdkWebSocketClient(HttpClient httpClient) {
            this.httpClient = httpClient;
        }

        @Override
        public List<String> exchange(URI uri, Map<String, String> headers, String payload, Duration timeout) {
            CollectorListener listener = new CollectorListener();
            WebSocket.Builder builder = httpClient.newWebSocketBuilder().connectTimeout(timeout);
            headers.forEach(builder::header);
            WebSocket webSocket = builder.buildAsync(uri, listener).join();
            webSocket.sendText(payload, true).join();
            try {
                listener.await(timeout);
            } finally {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            }
            return listener.messages;
        }
    }

    static final class CollectorListener implements WebSocket.Listener {
        private final List<String> messages = new ArrayList<>();
        private final CountDownLatch done = new CountDownLatch(1);
        private Throwable error;

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            messages.add(data.toString());
            webSocket.request(1);
            if ("[DONE]".contentEquals(data)) {
                done.countDown();
            }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            done.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            this.error = error;
            done.countDown();
        }

        void await(Duration timeout) {
            try {
                boolean completed = done.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!completed) {
                    throw new IllegalStateException("Provider WebSocket request timed out.");
                }
                if (error != null) {
                    throw new IllegalStateException("Provider WebSocket request failed.", error);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Provider WebSocket request interrupted.", exception);
            }
        }
    }
}
