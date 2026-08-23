package cn.lycode.ai.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lycode.ai.provider.RequestStyle;
import cn.lycode.ai.provider.TransportMode;
import cn.lycode.ai.transport.HttpSseProviderTransport;
import cn.lycode.contracts.context.AgentMessage;
import cn.lycode.contracts.context.ContextBudget;
import cn.lycode.contracts.context.MessageKind;
import cn.lycode.contracts.context.MessageRole;
import cn.lycode.contracts.context.TextContentBlock;
import cn.lycode.contracts.model.ApiStyle;
import cn.lycode.contracts.model.AssistantDone;
import cn.lycode.contracts.model.AssistantEventStream;
import cn.lycode.contracts.model.AssistantStart;
import cn.lycode.contracts.model.AssistantStreamEvent;
import cn.lycode.contracts.model.CostProfile;
import cn.lycode.contracts.model.ModelDescriptor;
import cn.lycode.contracts.model.ModelSelection;
import cn.lycode.contracts.model.TextDelta;
import cn.lycode.contracts.model.ThinkingLevel;
import cn.lycode.contracts.prompt.SystemPrompt;
import cn.lycode.contracts.security.AgentMode;
import cn.lycode.contracts.security.PermissionMode;
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

    private static cn.lycode.contracts.context.ContextSnapshot context() {
        return new cn.lycode.contracts.context.ContextSnapshot(
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
            PermissionMode.ASK,
            new ContextBudget(0, 128_000, 100_000, 16_384, 8_192, 0, 0, BigDecimal.ZERO)
        );
    }
}
