package cn.lypi.ai.transport;

import cn.lypi.ai.provider.ProviderEventStream;
import cn.lypi.ai.provider.ProviderRawEvent;
import cn.lypi.ai.provider.ProviderRequest;
import cn.lypi.ai.provider.ProviderTransport;
import cn.lypi.contracts.common.AbortSignal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WebSocketProviderTransport implements ProviderTransport {
    private final WebSocketClient client;

    public WebSocketProviderTransport() {
        this(new JdkWebSocketClient(HttpClient.newHttpClient()));
    }

    public WebSocketProviderTransport(WebSocketClient client) {
        this.client = client;
    }

    @Override
    public ProviderEventStream stream(ProviderRequest request, AbortSignal signal) {
        if (signal.aborted()) {
            return new EmptyProviderEventStream();
        }
        Duration timeout = request.timeout().orElse(Duration.ofSeconds(30));
        return client.open(request.uri(), request.headers(), request.body(), timeout, signal);
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
        ProviderEventStream open(URI uri, Map<String, String> headers, String payload, Duration timeout, AbortSignal signal);
    }

    private static final class JdkWebSocketClient implements WebSocketClient {
        private final HttpClient httpClient;

        private JdkWebSocketClient(HttpClient httpClient) {
            this.httpClient = httpClient;
        }

        @Override
        public ProviderEventStream open(URI uri, Map<String, String> headers, String payload, Duration timeout, AbortSignal signal) {
            QueueingListener listener = new QueueingListener();
            WebSocket.Builder builder = httpClient.newWebSocketBuilder().connectTimeout(timeout);
            headers.forEach(builder::header);
            WebSocket webSocket = builder.buildAsync(uri, listener).join();
            webSocket.sendText(payload, true).join();
            return new JdkWebSocketEventStream(listener, webSocket, timeout, signal);
        }
    }

    static final class QueueingListener implements WebSocket.Listener {
        private final LinkedBlockingQueue<SocketItem> queue = new LinkedBlockingQueue<>();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            queue.offer(SocketItem.message(data.toString()));
            webSocket.request(1);
            if ("[DONE]".contentEquals(data)) {
                queue.offer(SocketItem.terminal());
            }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            queue.offer(SocketItem.terminal());
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            queue.offer(SocketItem.error(error));
        }

        SocketItem take(Duration timeout) {
            try {
                SocketItem item = queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (item == null) {
                    throw new IllegalStateException("Provider WebSocket request timed out.");
                }
                if (item.error() != null) {
                    throw new IllegalStateException("Provider WebSocket request failed.", item.error());
                }
                return item;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Provider WebSocket request interrupted.", exception);
            }
        }
    }

    private record SocketItem(String message, Throwable error, boolean closed) {
        private static SocketItem message(String message) {
            return new SocketItem(message, null, false);
        }

        private static SocketItem error(Throwable error) {
            return new SocketItem(null, error, true);
        }

        private static SocketItem terminal() {
            return new SocketItem(null, null, true);
        }
    }

    private static final class JdkWebSocketEventStream implements ProviderEventStream {
        private final QueueingListener listener;
        private final WebSocket webSocket;
        private final Duration timeout;
        private final AbortSignal signal;
        private final AtomicBoolean closed = new AtomicBoolean();
        private boolean iteratorCreated;

        private JdkWebSocketEventStream(QueueingListener listener, WebSocket webSocket, Duration timeout, AbortSignal signal) {
            this.listener = listener;
            this.webSocket = webSocket;
            this.timeout = timeout;
            this.signal = signal;
        }

        @Override
        public Iterator<ProviderRawEvent> iterator() {
            if (iteratorCreated) {
                throw new IllegalStateException("Provider WebSocket stream is single-use.");
            }
            iteratorCreated = true;
            return new Iterator<>() {
                private ProviderRawEvent next;

                @Override
                public boolean hasNext() {
                    if (next != null) {
                        return true;
                    }
                    if (closed.get() || signal.aborted()) {
                        close();
                        return false;
                    }
                    SocketItem item = listener.take(timeout);
                    if (item.closed()) {
                        close();
                        return false;
                    }
                    next = new ProviderRawEvent(item.message());
                    return true;
                }

                @Override
                public ProviderRawEvent next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    ProviderRawEvent event = next;
                    next = null;
                    return event;
                }
            };
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            }
        }
    }

    private static final class EmptyProviderEventStream implements ProviderEventStream {
        @Override
        public Iterator<ProviderRawEvent> iterator() {
            return List.<ProviderRawEvent>of().iterator();
        }

        @Override
        public void close() {
        }
    }
}
