package cn.lycode.ai.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lycode.contracts.error.ModelProviderException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
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
    void discoversOptionalCapabilitiesFromCommonModelMetadataShapes() throws IOException {
        startServer(exchange -> respond(exchange, 200, """
            {"data":[
              {
                "id":"direct-fields",
                "context_window":131072,
                "max_output_tokens":16384,
                "supports_reasoning":false,
                "supports_image_input":false
              },
              {
                "id":"nested-fields",
                "context_length":262144,
                "top_provider":{"max_completion_tokens":32768},
                "supported_parameters":["reasoning_effort"],
                "architecture":{"input_modalities":["text","image"]}
              },
              {"id":"id-only","object":"model"}
            ]}
            """));

        RemoteModelDiscoveryClient client = new RemoteModelDiscoveryClient();

        List<DiscoveredModel> models = client.discoverModels(
            baseUrl(),
            "test-key",
            List.of("/models"),
            Duration.ofSeconds(2)
        );

        assertThat(models).containsExactly(
            new DiscoveredModel(
                "direct-fields",
                OptionalInt.of(131_072),
                OptionalInt.of(16_384),
                Optional.of(false),
                Optional.of(false)
            ),
            new DiscoveredModel(
                "nested-fields",
                OptionalInt.of(262_144),
                OptionalInt.of(32_768),
                Optional.of(true),
                Optional.of(true)
            ),
            DiscoveredModel.idOnly("id-only")
        );
    }

    @Test
    void readsTheFirstValidCapabilityFieldInDocumentedPriorityOrder() throws IOException {
        startServer(exchange -> respond(exchange, 200, """
            {"data":[
              {
                "id":"priority",
                "contextWindow":-1,
                "context_length":111,
                "context_window":222,
                "maxOutputTokens":"invalid",
                "max_output_tokens":333,
                "max_tokens":444,
                "top_provider":{"max_completion_tokens":555},
                "supportsThinking":"invalid",
                "supports_thinking":false,
                "supportsReasoning":true,
                "supports_reasoning":true,
                "supportsImageInput":"invalid",
                "supports_image_input":false,
                "input_modalities":["text","image"]
              },
              {
                "id":"camel-case",
                "contextWindow":64000,
                "maxOutputTokens":4096,
                "supportsThinking":true,
                "supportsImageInput":true
              },
              {
                "id":"remaining-aliases",
                "context_window":96000,
                "max_tokens":2048,
                "supportsReasoning":false,
                "input_modalities":["text"]
              }
            ]}
            """));

        List<DiscoveredModel> models = new RemoteModelDiscoveryClient().discoverModels(
            baseUrl(),
            "test-key",
            List.of("/models"),
            Duration.ofSeconds(2)
        );

        assertThat(models).containsExactly(
            new DiscoveredModel(
                "priority",
                OptionalInt.of(111),
                OptionalInt.of(333),
                Optional.of(false),
                Optional.of(false)
            ),
            new DiscoveredModel(
                "camel-case",
                OptionalInt.of(64_000),
                OptionalInt.of(4_096),
                Optional.of(true),
                Optional.of(true)
            ),
            new DiscoveredModel(
                "remaining-aliases",
                OptionalInt.of(96_000),
                OptionalInt.of(2_048),
                Optional.of(false),
                Optional.of(false)
            )
        );
    }

    @Test
    void treatsInvalidOrAbsentCapabilitiesAsUnknown() throws IOException {
        startServer(exchange -> respond(exchange, 200, """
            {"data":[
              {
                "id":"invalid",
                "contextWindow":0,
                "context_length":-1,
                "context_window":2147483648,
                "maxOutputTokens":"8192",
                "max_output_tokens":0,
                "max_tokens":-2,
                "top_provider":{"max_completion_tokens":9223372036854775808},
                "supportsThinking":"true",
                "supported_parameters":["temperature"],
                "supportsImageInput":1,
                "input_modalities":["audio"]
              },
              {
                "id":"inferred",
                "supported_parameters":["thinking"],
                "architecture":{"input_modalities":["text","image"]}
              },
              {
                "id":"reasoning-alias",
                "supported_parameters":["reasoning"]
              }
            ]}
            """));

        List<DiscoveredModel> models = new RemoteModelDiscoveryClient().discoverModels(
            baseUrl(),
            "test-key",
            List.of("/models"),
            Duration.ofSeconds(2)
        );

        assertThat(models.get(0)).isEqualTo(DiscoveredModel.idOnly("invalid"));
        assertThat(models.get(1).supportsThinking()).contains(true);
        assertThat(models.get(1).supportsImageInput()).contains(true);
        assertThat(models.get(2).supportsThinking()).contains(true);
        assertThat(models.get(2).supportsImageInput()).isEmpty();
    }

    @Test
    void keepsTheFirstCompleteRecordForDuplicateIdsAndPreservesLegacyIdOrder() throws IOException {
        startServer(exchange -> respond(exchange, 200, """
            {"data":[
              {"id":"model-a","context_window":128000,"supports_reasoning":true},
              {"id":"model-b"},
              {"id":"model-a","context_window":1,"supports_reasoning":false}
            ]}
            """));

        RemoteModelDiscoveryClient client = new RemoteModelDiscoveryClient();

        assertThat(client.discoverModels(baseUrl(), "test-key", List.of("/models"), Duration.ofSeconds(2)))
            .containsExactly(
                new DiscoveredModel(
                    "model-a",
                    OptionalInt.of(128_000),
                    OptionalInt.empty(),
                    Optional.of(true),
                    Optional.empty()
                ),
                DiscoveredModel.idOnly("model-b")
            );
        assertThat(client.discover(baseUrl(), "test-key", List.of("/models"), Duration.ofSeconds(2)))
            .containsExactly("model-a", "model-b");
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
        startServer(exchange -> respond(exchange, 200, "[\"model-a\",\"model-b\",\"model-a\"]"));

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
