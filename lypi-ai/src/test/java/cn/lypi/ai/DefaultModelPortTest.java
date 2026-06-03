package cn.lypi.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.error.ErrorSeverity;
import cn.lypi.contracts.error.ModelProviderException;
import cn.lypi.contracts.model.ApiStyle;
import cn.lypi.contracts.model.AssistantDone;
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
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class DefaultModelPortTest {
    @Test
    void streamDelegatesToAdapterForSelectedModel() {
        ModelDescriptor descriptor = descriptor("openai", "gpt-5", true, ApiStyle.OPENAI_COMPATIBLE);
        RecordingApiProvider apiProvider = new RecordingApiProvider(
            ApiStyle.OPENAI_COMPATIBLE,
            Stream.of(new AssistantStart("msg-1"), new TextDelta("hello"), new AssistantDone(Optional.empty(), Optional.of("stop")))
        );
        DefaultModelPort port = new DefaultModelPort(
            new DefaultModelRegistry(List.of(descriptor)),
            new DefaultApiProviderRegistry(List.of(apiProvider))
        );
        ContextSnapshot context = context(new ModelSelection("openai", "gpt-5", ThinkingLevel.HIGH), ThinkingLevel.HIGH);

        List<AssistantStreamEvent> events = port.stream(context, () -> false).toList();

        assertThat(events).containsExactly(
            new AssistantStart("msg-1"),
            new TextDelta("hello"),
            new AssistantDone(Optional.empty(), Optional.of("stop"))
        );
        assertThat(apiProvider.descriptor).isEqualTo(descriptor);
        assertThat(apiProvider.context).isEqualTo(context);
        assertThat(apiProvider.signal.aborted()).isFalse();
    }

    @Test
    void streamFailsWhenSelectedModelIsNotRegistered() {
        DefaultModelPort port = new DefaultModelPort(
            new DefaultModelRegistry(List.of(descriptor("openai", "gpt-5", true, ApiStyle.OPENAI_COMPATIBLE))),
            new DefaultApiProviderRegistry(List.of(new RecordingApiProvider(ApiStyle.OPENAI_COMPATIBLE, Stream.empty())))
        );
        ContextSnapshot context = context(new ModelSelection("openai", "gpt-4.1", ThinkingLevel.MEDIUM), ThinkingLevel.MEDIUM);

        assertThatThrownBy(() -> port.stream(context, () -> false).toList())
            .isInstanceOfSatisfying(ModelProviderException.class, error -> {
                assertThat(error.errorId()).isEqualTo("model.unavailable");
                assertThat(error.severity()).isEqualTo(ErrorSeverity.ERROR);
                assertThat(error.retryable()).isFalse();
            });
    }

    @Test
    void streamFailsWhenApiProviderIsMissing() {
        DefaultModelPort port = new DefaultModelPort(
            new DefaultModelRegistry(List.of(descriptor("anthropic", "claude-4", true, ApiStyle.ANTHROPIC))),
            new DefaultApiProviderRegistry(List.of())
        );
        ContextSnapshot context = context(new ModelSelection("anthropic", "claude-4", ThinkingLevel.MEDIUM), ThinkingLevel.MEDIUM);

        assertThatThrownBy(() -> port.stream(context, () -> false).toList())
            .isInstanceOfSatisfying(ModelProviderException.class, error -> {
                assertThat(error.errorId()).isEqualTo("api_provider.unavailable");
                assertThat(error.severity()).isEqualTo(ErrorSeverity.ERROR);
                assertThat(error.retryable()).isFalse();
            });
    }

    @Test
    void streamFailsWhenThinkingRequestedForModelWithoutThinkingSupport() {
        DefaultModelPort port = new DefaultModelPort(
            new DefaultModelRegistry(List.of(descriptor("openai", "gpt-5-mini", false, ApiStyle.OPENAI_COMPATIBLE))),
            new DefaultApiProviderRegistry(List.of(new RecordingApiProvider(ApiStyle.OPENAI_COMPATIBLE, Stream.empty())))
        );
        ContextSnapshot context = context(new ModelSelection("openai", "gpt-5-mini", ThinkingLevel.HIGH), ThinkingLevel.HIGH);

        assertThatThrownBy(() -> port.stream(context, () -> false).toList())
            .isInstanceOfSatisfying(ModelProviderException.class, error -> {
                assertThat(error.errorId()).isEqualTo("model.thinking_unsupported");
                assertThat(error.severity()).isEqualTo(ErrorSeverity.ERROR);
                assertThat(error.retryable()).isFalse();
            });
    }

    @Test
    void streamShortCircuitsWhenAlreadyAborted() {
        RecordingApiProvider apiProvider = new RecordingApiProvider(ApiStyle.OPENAI_COMPATIBLE, Stream.of(new TextDelta("ignored")));
        DefaultModelPort port = new DefaultModelPort(
            new DefaultModelRegistry(List.of(descriptor("openai", "gpt-5", true, ApiStyle.OPENAI_COMPATIBLE))),
            new DefaultApiProviderRegistry(List.of(apiProvider))
        );
        ContextSnapshot context = context(new ModelSelection("openai", "gpt-5", ThinkingLevel.MEDIUM), ThinkingLevel.MEDIUM);

        assertThat(port.stream(context, () -> true).toList()).isEmpty();
        assertThat(apiProvider.context).isNull();
    }

    private static ContextSnapshot context(ModelSelection selection, ThinkingLevel thinkingLevel) {
        return new ContextSnapshot(
            new SystemPrompt("system", List.of("test"), "hash"),
            List.of(),
            selection,
            thinkingLevel,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE,
            new ContextBudget(0, 128_000, 100_000, 16_384, 8_192, 0, 0, BigDecimal.ZERO)
        );
    }

    private static ModelDescriptor descriptor(String provider, String modelId, boolean supportsThinking, ApiStyle apiStyle) {
        return new ModelDescriptor(
            provider,
            modelId,
            URI.create("https://example.test/v1"),
            apiStyle,
            128_000,
            16_384,
            supportsThinking,
            false,
            new CostProfile(BigDecimal.ONE, BigDecimal.TEN, "USD"),
            Map.of()
        );
    }

    private static final class RecordingApiProvider implements ApiProvider {
        private final ApiStyle apiStyle;
        private final Stream<AssistantStreamEvent> events;
        private ContextSnapshot context;
        private ModelDescriptor descriptor;
        private AbortSignal signal;

        private RecordingApiProvider(ApiStyle apiStyle, Stream<AssistantStreamEvent> events) {
            this.apiStyle = apiStyle;
            this.events = events;
        }

        @Override
        public ApiStyle apiStyle() {
            return apiStyle;
        }

        @Override
        public Stream<AssistantStreamEvent> stream(ContextSnapshot context, ModelDescriptor descriptor, AbortSignal signal) {
            this.context = context;
            this.descriptor = descriptor;
            this.signal = signal;
            return events;
        }
    }
}
