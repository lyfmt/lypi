package cn.lypi.ai.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lypi.ai.provider.ProviderRawEvent;
import cn.lypi.ai.provider.ProviderRequest;
import cn.lypi.ai.provider.ProviderEventStream;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Iterator;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;
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
                Map.of("Authorization", "Bearer ${LYPI_TEST_TOKEN}"),
                body.toString()
            );

            List<ProviderRawEvent> events;
            try (ProviderEventStream stream = new HttpSseProviderTransport().stream(request, () -> false)) {
                events = StreamSupport.stream(stream.spliterator(), false).toList();
            }

            assertThat(authorization.get()).isEqualTo("Bearer ${LYPI_TEST_TOKEN}");
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
    void yieldsFirstSseEventBeforeResponseCloses() throws Exception {
        CountDownLatch firstEventWritten = new CountDownLatch(1);
        CountDownLatch allowClose = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/responses", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("""
                data: {"type":"response.output_text.delta","delta":"hello"}

                """.getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            firstEventWritten.countDown();
            try {
                allowClose.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        try {
            ProviderRequest request = new ProviderRequest(
                URI.create("http://localhost:" + server.getAddress().getPort() + "/v1/responses"),
                Map.of(),
                "{}"
            );
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Callable<ProviderRawEvent> readFirstEvent = () -> {
                    try (ProviderEventStream stream = new HttpSseProviderTransport().stream(request, () -> false)) {
                        Iterator<ProviderRawEvent> iterator = stream.iterator();
                        assertThat(iterator.hasNext()).isTrue();
                        return iterator.next();
                    }
                };

                var firstEvent = executor.submit(readFirstEvent);
                assertThat(firstEventWritten.await(1, TimeUnit.SECONDS)).isTrue();
                assertThat(firstEvent.get(1, TimeUnit.SECONDS))
                    .isEqualTo(new ProviderRawEvent("{\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}"));
            } finally {
                executor.shutdownNow();
                allowClose.countDown();
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doesNotLeakAuthorizationHeaderInHttpErrors() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/responses", exchange -> {
            byte[] body = "unsupported prompt payload".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ProviderRequest request = new ProviderRequest(
                URI.create("http://localhost:" + server.getAddress().getPort() + "/v1/responses"),
                Map.of("Authorization", "Bearer ${LYPI_TEST_TOKEN}"),
                "{}"
            );

            assertThatThrownBy(() -> {
                try (ProviderEventStream stream = new HttpSseProviderTransport().stream(request, () -> false)) {
                    StreamSupport.stream(stream.spliterator(), false).toList();
                }
            })
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 400")
                .hasMessageNotContaining("LYPI_TEST_TOKEN")
                .hasMessageNotContaining("prompt payload");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void includesProviderJsonErrorMessageWithoutLeakingRequestBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/responses", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = """
                {"error":{"message":"previous_response_id is only supported on Responses WebSocket v2","type":"invalid_request_error"}}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ProviderRequest request = new ProviderRequest(
                URI.create("http://localhost:" + server.getAddress().getPort() + "/v1/responses"),
                Map.of("Authorization", "Bearer ${LYPI_TEST_TOKEN}"),
                "{\"previous_response_id\":\"resp-secret\"}"
            );

            assertThatThrownBy(() -> {
                try (ProviderEventStream stream = new HttpSseProviderTransport().stream(request, () -> false)) {
                    StreamSupport.stream(stream.spliterator(), false).toList();
                }
            })
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 400")
                .hasMessageContaining("previous_response_id is only supported on Responses WebSocket v2")
                .hasMessageNotContaining("LYPI_TEST_TOKEN")
                .hasMessageNotContaining("resp-secret");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void returnsEmptyWhenAlreadyAborted() throws IOException {
        ProviderRequest request = new ProviderRequest(URI.create("http://localhost:1/v1/responses"), Map.of(), "{}");

        try (ProviderEventStream stream = new HttpSseProviderTransport().stream(request, () -> true)) {
            assertThat(StreamSupport.stream(stream.spliterator(), false).toList()).isEmpty();
        }
    }
}
