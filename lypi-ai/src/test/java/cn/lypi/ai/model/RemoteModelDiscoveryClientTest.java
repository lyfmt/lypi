package cn.lypi.ai.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lypi.contracts.error.ModelProviderException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RemoteModelDiscoveryClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void discoversOpenAiDataModelsAndSendsAuthorizationHeader() throws IOException {
        AtomicReference<String> authorization = new AtomicReference<>();
        startServer(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"data\":[{\"id\":\"gpt-5-mini\"},{\"id\":\"gpt-5\"}]}");
        });

        RemoteModelDiscoveryClient client = new RemoteModelDiscoveryClient();

        assertThat(client.discover(baseUrl(), "test-key", List.of("/models"), Duration.ofSeconds(2)))
            .containsExactly("gpt-5-mini", "gpt-5");
        assertThat(authorization.get()).isEqualTo("Bearer test-key");
    }

    @Test
    void triesFallbackModelPathWhenModelsPathIsMissing() throws IOException {
        startServer(exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/models")) {
                respond(exchange, 404, "");
                return;
            }
            respond(exchange, 200, "{\"models\":[{\"id\":\"fallback-model\"}]}");
        });

        RemoteModelDiscoveryClient client = new RemoteModelDiscoveryClient();

        assertThat(client.discover(baseUrl(), "test-key", List.of("/models", "/model"), Duration.ofSeconds(2)))
            .containsExactly("fallback-model");
    }

    @Test
    void parsesTopLevelStringArray() throws IOException {
        startServer(exchange -> respond(exchange, 200, "[\"model-a\",\"model-b\"]"));

        RemoteModelDiscoveryClient client = new RemoteModelDiscoveryClient();

        assertThat(client.discover(baseUrl(), "test-key", List.of("/models"), Duration.ofSeconds(2)))
            .containsExactly("model-a", "model-b");
    }

    @Test
    void throwsSanitizedErrorAfterEveryCandidatePathFails() throws IOException {
        List<String> requestedPaths = new CopyOnWriteArrayList<>();
        startServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            requestedPaths.add(path);
            respond(exchange, path.endsWith("/models") ? 401 : 404, "");
        });

        assertThatThrownBy(() -> new RemoteModelDiscoveryClient().discover(
            baseUrl(),
            "secret-key",
            List.of("/models", "/model"),
            Duration.ofSeconds(2)
        ))
            .isInstanceOfSatisfying(ModelProviderException.class, error ->
                assertThat(error.errorId()).isEqualTo("model.discovery_unavailable"))
            .hasMessageContaining("/v1/models")
            .hasMessageContaining("HTTP 401")
            .hasMessageContaining("/v1/model")
            .hasMessageContaining("HTTP 404")
            .hasMessageNotContaining("secret-key");

        assertThat(requestedPaths).containsExactly("/v1/models", "/v1/model");
    }

    @Test
    void throwsWhenSuccessfulResponsesContainNoUsableModelIds() throws IOException {
        startServer(exchange -> respond(exchange, 200, "{\"data\":[{\"object\":\"model\"}]}"));

        assertDiscoveryUnavailable(
            () -> new RemoteModelDiscoveryClient().discover(
                baseUrl(),
                "test-key",
                List.of("/models", "/model"),
                Duration.ofSeconds(2)
            ),
            "no usable model ids"
        );
    }

    @Test
    void throwsWhenEveryCandidateReturnsInvalidJson() throws IOException {
        startServer(exchange -> respond(exchange, 200, "{not-json"));

        assertDiscoveryUnavailable(
            () -> new RemoteModelDiscoveryClient().discover(
                baseUrl(),
                "test-key",
                List.of("/models", "/model"),
                Duration.ofSeconds(2)
            ),
            "invalid JSON"
        );
    }

    @Test
    void throwsWhenNetworkFails() {
        RemoteModelDiscoveryClient client = new RemoteModelDiscoveryClient();

        assertDiscoveryUnavailable(
            () -> client.discover(
                URI.create("http://127.0.0.1:1/v1"),
                "test-key",
                List.of("/models"),
                Duration.ofMillis(100)
            ),
            "network error"
        );
    }

    private static void assertDiscoveryUnavailable(ThrowingCall call, String diagnostic) {
        assertThatThrownBy(call::run)
            .isInstanceOfSatisfying(ModelProviderException.class, error ->
                assertThat(error.errorId()).isEqualTo("model.discovery_unavailable"))
            .hasMessageContaining(diagnostic)
            .hasMessageNotContaining("test-key");
    }

    private URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/");
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", handler::handle);
        server.createContext("/v1/model", handler::handle);
        server.start();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }
}
