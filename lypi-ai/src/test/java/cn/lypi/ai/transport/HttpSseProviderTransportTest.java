package cn.lypi.ai.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lypi.ai.provider.ProviderRawEvent;
import cn.lypi.ai.provider.ProviderRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HttpSseProviderTransportTest {
    @Test
    void postsJsonAndParsesSseDataLines() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/responses", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = """
                data: {"type":"response.output_text.delta","delta":"hello"}

                data: [DONE]

                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ObjectNode body = new ObjectMapper().createObjectNode().put("stream", true);
            ProviderRequest request = new ProviderRequest(
                URI.create("http://localhost:" + server.getAddress().getPort() + "/v1/responses"),
                Map.of("Authorization", "Bearer test-secret"),
                body.toString()
            );

            List<ProviderRawEvent> events = new HttpSseProviderTransport().stream(request, () -> false).toList();

            assertThat(authorization.get()).isEqualTo("Bearer test-secret");
            assertThat(requestBody.get()).contains("\"stream\":true");
            assertThat(events).containsExactly(
                new ProviderRawEvent("{\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}"),
                new ProviderRawEvent("[DONE]")
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doesNotLeakAuthorizationHeaderInHttpErrors() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/responses", exchange -> {
            byte[] body = "unsupported".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ProviderRequest request = new ProviderRequest(
                URI.create("http://localhost:" + server.getAddress().getPort() + "/v1/responses"),
                Map.of("Authorization", "Bearer test-secret"),
                "{}"
            );

            assertThatThrownBy(() -> new HttpSseProviderTransport().stream(request, () -> false).toList())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 400")
                .hasMessageNotContaining("test-secret");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void returnsEmptyWhenAlreadyAborted() throws IOException {
        ProviderRequest request = new ProviderRequest(URI.create("http://localhost:1/v1/responses"), Map.of(), "{}");

        assertThat(new HttpSseProviderTransport().stream(request, () -> true)).isEmpty();
    }
}
