package cn.lypi.ai.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class OpenAiProviderRealEndToEndTest {
    @Test
    @Timeout(90)
    void streamsFromConfiguredRealProvider() throws java.io.IOException {
        assumeTrue(Boolean.getBoolean("lypi.provider.e2e"), "Enable with -Dlypi.provider.e2e=true");
        RealProviderSettings settings = RealProviderSettings.fromSystemProperties();
        HttpSseProviderTransport sseTransport = new HttpSseProviderTransport();
        OpenAiCompatibleProviderAdapter adapter = new OpenAiCompatibleProviderAdapter(
            new OpenAiProviderConfig(
                "real-provider",
                settings.baseUrl(),
                Optional.empty(),
                "/v1/responses",
                settings.apiKey(),
                RequestStyle.RESPONSES,
                RequestStyle.RESPONSES,
                TransportMode.SSE,
                Duration.ofSeconds(60),
                0,
                Map.of()
            ),
            (request, signal) -> {
                throw new IllegalStateException("Real provider e2e uses HTTP SSE transport only.");
            },
            sseTransport,
            sseTransport
        );

        List<AssistantStreamEvent> events = collect(adapter.stream(context(settings), descriptor(settings), () -> false));
        String assistantText = events.stream()
            .filter(TextDelta.class::isInstance)
            .map(TextDelta.class::cast)
            .map(TextDelta::text)
            .reduce("", String::concat)
            .strip();
        Path outputPath = Path.of("target", "real-provider-response.txt");
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, assistantText + System.lineSeparator(), StandardCharsets.UTF_8);

        assertThat(events)
            .filteredOn(TextDelta.class::isInstance)
            .map(TextDelta.class::cast)
            .extracting(TextDelta::text)
            .anySatisfy(text -> assertThat(text).isNotBlank());
        assertThat(events).anySatisfy(event -> assertThat(event).isInstanceOf(AssistantDone.class));
        assertThat(outputPath).exists();
        assertThat(Files.readString(outputPath, StandardCharsets.UTF_8)).isNotBlank();
    }

    private static ModelDescriptor descriptor(RealProviderSettings settings) {
        return new ModelDescriptor(
            "real-provider",
            settings.model(),
            settings.baseUrl(),
            ApiStyle.OPENAI_COMPATIBLE,
            128_000,
            16_384,
            true,
            false,
            new CostProfile(BigDecimal.ZERO, BigDecimal.ZERO, "USD"),
            Map.of()
        );
    }

    private static List<AssistantStreamEvent> collect(AssistantEventStream stream) {
        try (stream) {
            return StreamSupport.stream(stream.spliterator(), false).toList();
        }
    }

    private static ContextSnapshot context(RealProviderSettings settings) {
        return new ContextSnapshot(
            new SystemPrompt("system", List.of("test"), "hash"),
            List.of(new AgentMessage(
                "msg-1",
                MessageRole.USER,
                MessageKind.TEXT,
                List.of(new TextContentBlock("请用一句简短中文回复：真实 provider 测试通过")),
                Instant.EPOCH,
                Optional.empty(),
                Optional.empty()
            )),
            new ModelSelection("real-provider", settings.model(), settings.thinkingLevel()),
            settings.thinkingLevel(),
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE,
            new ContextBudget(0, 128_000, 100_000, 16_384, 8_192, 0, 0, BigDecimal.ZERO)
        );
    }

    private record RealProviderSettings(
        URI baseUrl,
        String apiKey,
        String model,
        ThinkingLevel thinkingLevel
    ) {
        private static RealProviderSettings fromSystemProperties() {
            return new RealProviderSettings(
                requiredUri("lypi.provider.e2e.base-url"),
                required("lypi.provider.e2e.api-key"),
                required("lypi.provider.e2e.model"),
                ThinkingLevel.valueOf(System.getProperty("lypi.provider.e2e.thinking", "OFF").toUpperCase(java.util.Locale.ROOT))
            );
        }

        private static URI requiredUri(String key) {
            return URI.create(required(key));
        }

        private static String required(String key) {
            String value = System.getProperty(key);
            assumeTrue(value != null && !value.isBlank(), "Missing required system property: " + key);
            return value;
        }
    }
}
