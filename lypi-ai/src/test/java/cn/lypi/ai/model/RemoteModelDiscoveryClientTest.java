package cn.lypi.ai.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
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
        assertThat(client.lastFailure()).isEmpty();
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
    void returnsEmptyListWhenNetworkFails() {
        RemoteModelDiscoveryClient client = new RemoteModelDiscoveryClient();

        assertThat(client.discover(URI.create("http://127.0.0.1:1/v1"), "test-key", List.of("/models"), Duration.ofMillis(100)))
            .isEmpty();
        assertThat(client.lastFailure()).hasValueSatisfying(failure -> assertThat(failure).contains("Discovery"));
        assertThat(client.lastFailure().orElseThrow()).doesNotContain("test-key");
    }

    @Test
    void recordsHttpFailureWithoutLeakingApiKey() throws IOException {
        startServer(exchange -> respond(exchange, 401, "{\"error\":\"unauthorized\"}"));
        RemoteModelDiscoveryClient client = new RemoteModelDiscoveryClient();

        assertThat(client.discover(baseUrl(), "secret-key", List.of("/models"), Duration.ofSeconds(2)))
            .isEmpty();

        assertThat(client.lastFailure()).hasValueSatisfying(failure -> assertThat(failure).contains("HTTP 401"));
        assertThat(client.lastFailure().orElseThrow()).doesNotContain("secret-key");
        assertThat(client.lastFailure().orElseThrow()).contains("/v1/models");
    }

    @Test
    void recordsParseFailure() throws IOException {
        startServer(exchange -> respond(exchange, 200, "{not-json"));
        RemoteModelDiscoveryClient client = new RemoteModelDiscoveryClient();

        assertThat(client.discover(baseUrl(), "test-key", List.of("/models"), Duration.ofSeconds(2)))
            .isEmpty();

        assertThat(client.lastFailure()).hasValueSatisfying(failure -> assertThat(failure).contains("parse"));
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
}
