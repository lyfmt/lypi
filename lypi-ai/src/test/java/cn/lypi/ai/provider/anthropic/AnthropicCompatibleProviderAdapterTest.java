package cn.lypi.ai.provider.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lypi.ai.provider.ListProviderEventStream;
import cn.lypi.ai.provider.ProviderEventStream;
import cn.lypi.ai.provider.ProviderRawEvent;
import cn.lypi.ai.provider.ProviderRequest;
import cn.lypi.ai.provider.ProviderTransport;
import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.error.ModelProviderException;
import cn.lypi.contracts.model.ApiStyle;
import cn.lypi.contracts.model.AssistantDone;
import cn.lypi.contracts.model.AssistantEventStream;
import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.model.CostProfile;
import cn.lypi.contracts.model.ModelDescriptor;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.TextDelta;
import cn.lypi.contracts.model.ThinkingDelta;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.model.TokenUsage;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.tool.ToolDescriptor;
import cn.lypi.contracts.tool.ToolRegistrySnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class AnthropicCompatibleProviderAdapterTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void sendsMessagesRequestWithAnthropicHeadersAndRuntimeTools() throws Exception {
        RecordingTransport transport = RecordingTransport.events(
            "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\"}}",
            "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"hello\"}}",
            "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"reasoned\"}}",
            "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"input_tokens\":7,\"output_tokens\":3,\"cache_read_input_tokens\":1}}",
            "{\"type\":\"message_stop\"}"
        );
        AnthropicCompatibleProviderAdapter adapter = new AnthropicCompatibleProviderAdapter(config("test-key"), transport);
        ToolRegistrySnapshot tools = new ToolRegistrySnapshot(List.of(new ToolDescriptor(
            "read",
            List.of("cat"),
            "读取文件内容。",
            new JsonSchema(Map.of(
                "type", "object",
                "properties", Map.of("path", Map.of("type", "string")),
                "required", List.of("path")
            )),
            true,
            false
        )));

        List<AssistantStreamEvent> events;
        try (AssistantEventStream stream = adapter.stream(context(), descriptor(), tools, () -> false)) {
            events = StreamSupport.stream(stream.spliterator(), false).toList();

            assertThat(stream.result().messageId()).isEqualTo("msg_1");
            assertThat(stream.result().completed()).isTrue();
            assertThat(stream.result().usage()).contains(new TokenUsage(7, 3, 1, 0));
            assertThat(stream.result().stopReason()).contains("end_turn");
        }

        assertThat(events).contains(
            new TextDelta("hello"),
            new ThinkingDelta("reasoned"),
            new AssistantDone(Optional.of(new TokenUsage(7, 3, 1, 0)), Optional.of("end_turn"))
        );
        assertThat(transport.requests).hasSize(1);
        ProviderRequest request = transport.requests.getFirst();
        assertThat(request.uri()).hasToString("https://api.anthropic.test/v1/messages");
        assertThat(request.headers())
            .containsEntry("x-api-key", "test-key")
            .containsEntry("anthropic-version", "2023-06-01");
        assertThat(request.headers()).doesNotContainKey("Authorization");
        JsonNode body = OBJECT_MAPPER.readTree(request.body());
        assertThat(body.at("/messages/0/content/0/text").asText()).isEqualTo("hello");
        assertThat(body.at("/tools/0/name").asText()).isEqualTo("read");
        assertThat(body.at("/tools/0/input_schema/properties/path/type").asText()).isEqualTo("string");
        assertThat(body.get("api_key")).isNull();
    }

    @Test
    void doesNotOpenTransportUntilStreamIsConsumed() {
        RecordingTransport transport = RecordingTransport.events(
            "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\"}}"
        );
        AnthropicCompatibleProviderAdapter adapter = new AnthropicCompatibleProviderAdapter(config("test-key"), transport);

        try (var ignored = adapter.stream(context(), descriptor(), () -> false)) {
            assertThat(transport.requests).isEmpty();
        }
    }

    @Test
    void failsClearlyWhenApiKeyIsMissing() {
        AnthropicCompatibleProviderAdapter adapter = new AnthropicCompatibleProviderAdapter(
            config(""),
            RecordingTransport.events()
        );

        assertThatThrownBy(() -> collect(adapter.stream(context(), descriptor(), () -> false)))
            .isInstanceOfSatisfying(ModelProviderException.class, error -> {
                assertThat(error.errorId()).isEqualTo("provider.api_key_missing");
                assertThat(error.getMessage()).doesNotContain("x-api-key");
            });
    }

    @Test
    void retriesProviderRequestBeforeReportingFailure() {
        RecordingTransport transport = RecordingTransport.fail("Provider HTTP 429: rate limit");
        AnthropicCompatibleProviderAdapter adapter = new AnthropicCompatibleProviderAdapter(
            new AnthropicProviderConfig(
                "anthropic",
                URI.create("https://api.anthropic.test/v1"),
                "test-key",
                "2023-06-01",
                Duration.ofSeconds(30),
                1,
                Map.of()
            ),
            transport
        );

        List<AssistantStreamEvent> events = collect(adapter.stream(context(), descriptor(), () -> false));

        assertThat(transport.requests).hasSize(2);
        assertThat(events)
            .filteredOn(cn.lypi.contracts.model.ProviderRetryNotice.class::isInstance)
            .singleElement()
            .isInstanceOf(cn.lypi.contracts.model.ProviderRetryNotice.class);
        assertThat(events)
            .filteredOn(cn.lypi.contracts.model.AssistantError.class::isInstance)
            .singleElement()
            .isInstanceOf(cn.lypi.contracts.model.AssistantError.class);
    }

    @Test
    void stopsAfterProviderErrorEventWithoutReopeningTransport() {
        RecordingTransport transport = RecordingTransport.events(
            "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"Bad request\"}}"
        );
        AnthropicCompatibleProviderAdapter adapter = new AnthropicCompatibleProviderAdapter(config("test-key"), transport);

        try (AssistantEventStream stream = adapter.stream(context(), descriptor(), () -> false)) {
            Iterator<AssistantStreamEvent> iterator = stream.iterator();

            assertThat(iterator.hasNext()).isTrue();
            assertThat(iterator.next()).isInstanceOf(cn.lypi.contracts.model.AssistantError.class);
            assertThat(iterator.hasNext()).isFalse();
            assertThat(stream.result().error())
                .hasValue(new cn.lypi.contracts.model.AssistantError("invalid_request_error", "Bad request"));
        }
        assertThat(transport.requests).hasSize(1);
    }

    private static List<AssistantStreamEvent> collect(AssistantEventStream stream) {
        try (stream) {
            return StreamSupport.stream(stream.spliterator(), false).toList();
        }
    }

    private static AnthropicProviderConfig config(String apiKey) {
        return new AnthropicProviderConfig(
            "anthropic",
            URI.create("https://api.anthropic.test/v1"),
            apiKey,
            "2023-06-01",
            Duration.ofSeconds(30),
            0,
            Map.of()
        );
    }

    private static ModelDescriptor descriptor() {
        return new ModelDescriptor(
            "anthropic",
            "claude-sonnet-4-5",
            URI.create("https://api.anthropic.test/v1"),
            ApiStyle.ANTHROPIC,
            200_000,
            16_384,
            true,
            false,
            new CostProfile(BigDecimal.ZERO, BigDecimal.ZERO, "USD"),
            Map.of()
        );
    }

    private static ContextSnapshot context() {
        return new ContextSnapshot(
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
            new ModelSelection("anthropic", "claude-sonnet-4-5", ThinkingLevel.HIGH),
            ThinkingLevel.HIGH,
            AgentMode.EXECUTE,
            PermissionMode.ASK,
            new ContextBudget(0, 200_000, 160_000, 16_384, 8_192, 0, 0, BigDecimal.ZERO)
        );
    }

    private static final class RecordingTransport implements ProviderTransport {
        private final List<String> events;
        private final RuntimeException failure;
        private final List<ProviderRequest> requests = new ArrayList<>();

        private RecordingTransport(List<String> events, RuntimeException failure) {
            this.events = events;
            this.failure = failure;
        }

        private static RecordingTransport events(String... events) {
            return new RecordingTransport(List.of(events), null);
        }

        private static RecordingTransport fail(String message) {
            return new RecordingTransport(List.of(), new IllegalStateException(message));
        }

        @Override
        public ProviderEventStream stream(ProviderRequest request, AbortSignal signal) {
            requests.add(request);
            if (failure != null) {
                throw failure;
            }
            return new ListProviderEventStream(events.stream().map(ProviderRawEvent::new).toList());
        }
    }
}
