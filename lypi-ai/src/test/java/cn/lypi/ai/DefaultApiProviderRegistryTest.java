package cn.lypi.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.error.ModelProviderException;
import cn.lypi.contracts.model.ApiStyle;
import cn.lypi.contracts.model.AssistantDone;
import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.model.CostProfile;
import cn.lypi.contracts.model.ModelDescriptor;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.TextDelta;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.tool.ToolDescriptor;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class DefaultApiProviderRegistryTest {
    @Test
    void findMatchesApiStyle() {
        RecordingApiProvider openAi = new RecordingApiProvider(ApiStyle.OPENAI_COMPATIBLE);
        DefaultApiProviderRegistry registry = new DefaultApiProviderRegistry(List.of(openAi));

        assertThat(registry.find(ApiStyle.OPENAI_COMPATIBLE)).contains(openAi);
        assertThat(registry.find(ApiStyle.ANTHROPIC)).isEmpty();
    }

    @Test
    void rejectsDuplicateApiStyle() {
        RecordingApiProvider first = new RecordingApiProvider(ApiStyle.OPENAI_COMPATIBLE);
        RecordingApiProvider second = new RecordingApiProvider(ApiStyle.OPENAI_COMPATIBLE);

        assertThatThrownBy(() -> new DefaultApiProviderRegistry(List.of(first, second)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("OPENAI_COMPATIBLE");
    }

    @Test
    void providerAdapterApiProviderRoutesByDescriptorProvider() {
        RecordingProviderAdapter openAi = new RecordingProviderAdapter("openai", Stream.of(new TextDelta("openai")));
        RecordingProviderAdapter fixture = new RecordingProviderAdapter("fixture", Stream.of(new TextDelta("fixture")));
        ProviderAdapterApiProvider apiProvider = new ProviderAdapterApiProvider(
            ApiStyle.OPENAI_COMPATIBLE,
            List.of(openAi, fixture)
        );
        ModelDescriptor descriptor = descriptor("fixture", "gpt-5-mini");
        ContextSnapshot context = context();

        assertThat(apiProvider.stream(context, descriptor, () -> false).toList())
            .containsExactly(new TextDelta("fixture"));
        assertThat(fixture.descriptor).isEqualTo(descriptor);
        assertThat(openAi.descriptor).isNull();
    }

    @Test
    void providerAdapterApiProviderFailsWhenProviderConfigIsMissing() {
        ProviderAdapterApiProvider apiProvider = new ProviderAdapterApiProvider(
            ApiStyle.OPENAI_COMPATIBLE,
            List.of(new RecordingProviderAdapter("openai", Stream.of(new AssistantDone(Optional.empty(), Optional.of("stop")))))
        );

        assertThatThrownBy(() -> apiProvider.stream(context(), descriptor("fixture", "gpt-5-mini"), () -> false).toList())
            .isInstanceOfSatisfying(ModelProviderException.class, error ->
                assertThat(error.errorId()).isEqualTo("provider.adapter_unavailable"));
    }

    private record RecordingApiProvider(ApiStyle apiStyle) implements ApiProvider {
        @Override
        public Stream<AssistantStreamEvent> stream(
            ContextSnapshot context,
            ModelDescriptor descriptor,
            List<ToolDescriptor> tools,
            AbortSignal signal
        ) {
            return Stream.empty();
        }
    }

    private static final class RecordingProviderAdapter implements ProviderAdapter {
        private final String provider;
        private final Stream<AssistantStreamEvent> events;
        private ModelDescriptor descriptor;

        private RecordingProviderAdapter(String provider, Stream<AssistantStreamEvent> events) {
            this.provider = provider;
            this.events = events;
        }

        @Override
        public String provider() {
            return provider;
        }

        @Override
        public Stream<AssistantStreamEvent> stream(
            ContextSnapshot context,
            ModelDescriptor descriptor,
            List<ToolDescriptor> tools,
            AbortSignal signal
        ) {
            this.descriptor = descriptor;
            return events;
        }
    }

    private static ModelDescriptor descriptor(String provider, String modelId) {
        return new ModelDescriptor(
            provider,
            modelId,
            URI.create("https://example.test/v1"),
            ApiStyle.OPENAI_COMPATIBLE,
            128_000,
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
            List.of(),
            new ModelSelection("fixture", "gpt-5-mini", ThinkingLevel.OFF),
            ThinkingLevel.OFF,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE,
            new ContextBudget(0, 128_000, 100_000, 16_384, 8_192, 0, 0, BigDecimal.ZERO)
        );
    }
}
