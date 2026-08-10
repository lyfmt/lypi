package cn.lypi.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.error.ModelProviderException;
import cn.lypi.contracts.model.ApiStyle;
import cn.lypi.contracts.model.AssistantEventStream;
import cn.lypi.contracts.model.AssistantStreamResult;
import cn.lypi.contracts.model.CostProfile;
import cn.lypi.contracts.model.ModelDescriptor;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProviderAdapterApiProviderTest {
    @Test
    void replacesAdapterForTheSameProvider() {
        RecordingAdapter oldAdapter = new RecordingAdapter("login-example");
        RecordingAdapter newAdapter = new RecordingAdapter("login-example");
        ProviderAdapterApiProvider provider = new ProviderAdapterApiProvider(
            ApiStyle.OPENAI_COMPATIBLE,
            List.of(oldAdapter)
        );

        provider.replaceAdapter(newAdapter);
        try (AssistantEventStream ignored = provider.stream(context(), descriptor(), () -> false)) {
            assertThat(newAdapter.calls).isEqualTo(1);
            assertThat(oldAdapter.calls).isZero();
        }
    }

    @Test
    void failsForAnAdapterThatHasNotBeenRegistered() {
        ProviderAdapterApiProvider provider = new ProviderAdapterApiProvider(
            ApiStyle.OPENAI_COMPATIBLE,
            List.of()
        );

        assertThatThrownBy(() -> provider.stream(context(), descriptor(), () -> false))
            .isInstanceOfSatisfying(ModelProviderException.class, error ->
                assertThat(error.errorId()).isEqualTo("provider.adapter_unavailable"));
    }

    private static ModelDescriptor descriptor() {
        return new ModelDescriptor(
            "login-example",
            "model-a",
            URI.create("https://example.test/v1"),
            ApiStyle.OPENAI_COMPATIBLE,
            0,
            0,
            false,
            false,
            new CostProfile(BigDecimal.ZERO, BigDecimal.ZERO, "USD"),
            Map.of()
        );
    }

    private static ContextSnapshot context() {
        return new ContextSnapshot(
            new SystemPrompt("system", List.of(), "hash"),
            List.of(),
            new ModelSelection("login-example", "model-a", ThinkingLevel.OFF),
            ThinkingLevel.OFF,
            AgentMode.EXECUTE,
            PermissionMode.ASK,
            new ContextBudget(0, 1, 1, 1, 1, 0, 0, BigDecimal.ZERO)
        );
    }

    private static final class RecordingAdapter implements ProviderAdapter {
        private final String provider;
        private int calls;

        private RecordingAdapter(String provider) {
            this.provider = provider;
        }

        @Override
        public String provider() {
            return provider;
        }

        @Override
        public AssistantEventStream stream(ContextSnapshot context, ModelDescriptor descriptor, AbortSignal signal) {
            calls++;
            return new AssistantEventStream() {
                @Override
                public java.util.Iterator<cn.lypi.contracts.model.AssistantStreamEvent> iterator() {
                    return List.<cn.lypi.contracts.model.AssistantStreamEvent>of().iterator();
                }

                @Override
                public AssistantStreamResult result() {
                    return new AssistantStreamResult("", List.of(), Optional.empty(), Optional.empty(), true, false, Optional.empty());
                }

                @Override
                public void close() {
                }
            };
        }
    }
}
