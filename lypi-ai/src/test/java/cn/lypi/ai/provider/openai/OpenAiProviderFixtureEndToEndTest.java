package cn.lypi.ai.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.ai.provider.RequestStyle;
import cn.lypi.ai.provider.TransportMode;
import cn.lypi.ai.transport.HttpSseProviderTransport;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.model.ApiStyle;
import cn.lypi.contracts.model.AssistantDone;
import cn.lypi.contracts.model.AssistantEventStream;
import cn.lypi.contracts.model.AssistantStart;
import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.model.CostProfile;
import cn.lypi.contracts.model.ModelDescriptor;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.TextDelta;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OpenAiProviderFixtureEndToEndTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void streamsResponsesFixtureThroughHttpSseTransport() throws IOException {
        startServer("/v1/responses", fixture("openai-responses-stream.sse"));
        OpenAiCompatibleProviderAdapter adapter = adapter(RequestStyle.RESPONSES);

        List<AssistantStreamEvent> events = collect(adapter.stream(context(), descriptor(), () -> false));

        assertThat(events)
            .contains(new AssistantStart("resp-fixture"), new TextDelta("hello"))
            .anySatisfy(event -> assertThat(event).isInstanceOf(AssistantDone.class));
    }

    @Test
    void streamsChatCompletionsFixtureThroughHttpSseTransport() throws IOException {
        startServer("/v1/chat/completions", fixture("openai-chat-stream.sse"));
        OpenAiCompatibleProviderAdapter adapter = adapter(RequestStyle.CHAT_COMPLETIONS);

        List<AssistantStreamEvent> events = collect(adapter.stream(context(), descriptor(), () -> false));

        assertThat(events)
            .contains(new AssistantStart("chatcmpl-fixture"), new TextDelta("hello"), new AssistantDone(Optional.empty(), Optional.of("stop")));
    }

    private OpenAiCompatibleProviderAdapter adapter(RequestStyle requestStyle) {
        HttpSseProviderTransport sseTransport = new HttpSseProviderTransport();
        return new OpenAiCompatibleProviderAdapter(
            new OpenAiProviderConfig(
                "openai",
                baseUrl(),
                Optional.empty(),
                "/v1/responses",
                "fixture-key",
                requestStyle,
                requestStyle,
                TransportMode.SSE,
                Duration.ofSeconds(5),
                0,
                Map.of()
            ),
            (request, signal) -> {
                throw new IllegalStateException("WebSocket transport should not be used in fixture e2e.");
            },
            sseTransport,
            sseTransport
        );
    }

    private static List<AssistantStreamEvent> collect(AssistantEventStream stream) {
        try (stream) {
            return StreamSupport.stream(stream.spliterator(), false).toList();
        }
    }

    private URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    }

    private void startServer(String path, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> respond(exchange, body));
        server.start();
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String fixture(String name) throws IOException {
        try (var stream = OpenAiProviderFixtureEndToEndTest.class.getResourceAsStream("/provider-fixtures/" + name)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static ModelDescriptor descriptor() {
        return new ModelDescriptor(
            "openai",
            "gpt-5-mini",
            URI.create("https://api.openai.test/v1"),
            ApiStyle.OPENAI_COMPATIBLE,
            128_000,
            16_384,
            true,
            false,
            new CostProfile(BigDecimal.ZERO, BigDecimal.ZERO, "USD"),
            Map.of()
        );
    }

    private static cn.lypi.contracts.context.ContextSnapshot context() {
        return new cn.lypi.contracts.context.ContextSnapshot(
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
            new ModelSelection("openai", "gpt-5-mini", ThinkingLevel.OFF),
            ThinkingLevel.OFF,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE,
            new ContextBudget(0, 128_000, 100_000, 16_384, 8_192, 0, 0, BigDecimal.ZERO)
        );
    }
}
