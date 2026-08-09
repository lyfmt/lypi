package cn.lypi.ai.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cn.lypi.ai.model.RemoteModelDiscoveryClient;
import cn.lypi.ai.provider.ProviderTransport;
import cn.lypi.ai.provider.RequestStyle;
import cn.lypi.ai.provider.TransportMode;
import cn.lypi.ai.transport.HttpSseProviderTransport;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.model.ApiStyle;
import cn.lypi.contracts.model.AssistantDone;
import cn.lypi.contracts.model.AssistantEventStream;
import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.model.CostProfile;
import cn.lypi.contracts.model.ModelDescriptor;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.TextDelta;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class OpenAiCompatibleChannelRealEndToEndTest {
    private static final URI BASE_URL = URI.create("https://opencode.ai/zen/go/v1/");

    @Test
    @Timeout(120)
    void discoversModelsAndStreamsChatCompletions() throws IOException {
        assumeTrue(Boolean.getBoolean("lypi.opencode.e2e"), "Enable with -Dlypi.opencode.e2e=true");
        String apiKey = apiKey();
        List<String> modelIds = new RemoteModelDiscoveryClient().discover(
            BASE_URL,
            apiKey,
            List.of("/models", "/model"),
            Duration.ofSeconds(30)
        );
        assertThat(modelIds.isEmpty()).as("OpenCode model discovery returned no models").isFalse();

        String configuredModel = System.getProperty("lypi.opencode.e2e.model", "").trim();
        String modelId = configuredModel.isEmpty() ? modelIds.getFirst() : configuredModel;
        if (!modelIds.contains(modelId)) {
            throw new IllegalStateException("Configured OpenCode E2E model is unavailable.");
        }

        HttpSseProviderTransport chatTransport = new HttpSseProviderTransport();
        OpenAiCompatibleProviderAdapter adapter = new OpenAiCompatibleProviderAdapter(
            config(apiKey),
            unusedTransport(),
            unusedTransport(),
            chatTransport
        );

        List<AssistantStreamEvent> events;
        try {
            events = collect(adapter.stream(context(modelId), descriptor(modelId), () -> false));
        } catch (RuntimeException ignored) {
            throw new AssertionError("OpenCode Chat Completions stream failed.");
        }

        boolean receivedText = events.stream()
            .anyMatch(event -> event instanceof TextDelta text && !text.text().isBlank());
        boolean receivedDone = events.stream().anyMatch(AssistantDone.class::isInstance);
        assertThat(receivedText).as("OpenCode stream emitted non-empty text").isTrue();
        assertThat(receivedDone).as("OpenCode stream emitted a completion event").isTrue();
    }

    private static String apiKey() throws IOException {
        Path authFile = Path.of(System.getProperty("user.home"), ".pi", "agent", "auth.json");
        JsonNode credential = new ObjectMapper().readTree(authFile.toFile()).path("opencode-go");
        String apiKey = credential.path("key").asText();
        if (apiKey.isBlank()) {
            throw new IllegalStateException("Missing opencode-go API key credential.");
        }
        return apiKey;
    }

    private static OpenAiProviderConfig config(String apiKey) {
        return new OpenAiProviderConfig(
            "opencode-go",
            BASE_URL,
            Optional.empty(),
            "/v1/responses",
            apiKey,
            RequestStyle.CHAT_COMPLETIONS,
            RequestStyle.CHAT_COMPLETIONS,
            TransportMode.SSE,
            Duration.ofSeconds(60),
            0,
            Map.of()
        );
    }

    private static ProviderTransport unusedTransport() {
        return (request, signal) -> {
            throw new IllegalStateException("OpenCode E2E must use Chat Completions over HTTP SSE.");
        };
    }

    private static ModelDescriptor descriptor(String modelId) {
        return new ModelDescriptor(
            "opencode-go",
            modelId,
            BASE_URL,
            ApiStyle.OPENAI_COMPATIBLE,
            128_000,
            4_096,
            false,
            false,
            new CostProfile(BigDecimal.ZERO, BigDecimal.ZERO, "USD"),
            Map.of()
        );
    }

    private static ContextSnapshot context(String modelId) {
        return new ContextSnapshot(
            new SystemPrompt("system", List.of("test"), "hash"),
            List.of(new AgentMessage(
                "msg-1",
                MessageRole.USER,
                MessageKind.TEXT,
                List.of(new TextContentBlock("请用一句简短中文回复：连接测试通过")),
                Instant.EPOCH,
                Optional.empty(),
                Optional.empty()
            )),
            new ModelSelection("opencode-go", modelId, ThinkingLevel.OFF),
            ThinkingLevel.OFF,
            AgentMode.EXECUTE,
            PermissionMode.ASK,
            new ContextBudget(0, 128_000, 100_000, 4_096, 2_048, 0, 0, BigDecimal.ZERO)
        );
    }

    private static List<AssistantStreamEvent> collect(AssistantEventStream stream) {
        try (stream) {
            return StreamSupport.stream(stream.spliterator(), false).toList();
        }
    }
}
