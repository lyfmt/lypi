package cn.lycode.ai.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cn.lycode.ai.provider.RequestStyle;
import cn.lycode.ai.provider.TransportMode;
import cn.lycode.ai.transport.HttpSseProviderTransport;
import cn.lycode.contracts.context.AgentMessage;
import cn.lycode.contracts.context.ContextBudget;
import cn.lycode.contracts.context.ContextSnapshot;
import cn.lycode.contracts.context.MessageKind;
import cn.lycode.contracts.context.MessageRole;
import cn.lycode.contracts.context.TextContentBlock;
import cn.lycode.contracts.model.ApiStyle;
import cn.lycode.contracts.model.AssistantDone;
import cn.lycode.contracts.model.AssistantEventStream;
import cn.lycode.contracts.model.AssistantStreamEvent;
import cn.lycode.contracts.model.CostProfile;
import cn.lycode.contracts.model.ModelDescriptor;
import cn.lycode.contracts.model.ModelSelection;
import cn.lycode.contracts.model.TextDelta;
import cn.lycode.contracts.model.ThinkingLevel;
import cn.lycode.contracts.prompt.SystemPrompt;
import cn.lycode.contracts.security.AgentMode;
import cn.lycode.contracts.security.PermissionMode;
import java.math.BigDecimal;
import java.net.URI;
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
    void streamsFromConfiguredRealProvider() {
        assumeTrue(Boolean.getBoolean("lycode.provider.e2e"), "Enable with -Dlycode.provider.e2e=true");
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

        assertThat(events)
            .filteredOn(TextDelta.class::isInstance)
            .map(TextDelta.class::cast)
            .extracting(TextDelta::text)
            .anySatisfy(text -> assertThat(text).isNotBlank());
        assertThat(events).anySatisfy(event -> assertThat(event).isInstanceOf(AssistantDone.class));
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
            PermissionMode.ASK,
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
                requiredUri("lycode.provider.e2e.base-url"),
                required("lycode.provider.e2e.api-key"),
                required("lycode.provider.e2e.model"),
                ThinkingLevel.valueOf(System.getProperty("lycode.provider.e2e.thinking", "OFF").toUpperCase(java.util.Locale.ROOT))
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
