package cn.lypi.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.model.ApiStyle;
import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.model.ModelDescriptor;
import java.util.List;
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

    private record RecordingApiProvider(ApiStyle apiStyle) implements ApiProvider {
        @Override
        public Stream<AssistantStreamEvent> stream(ContextSnapshot context, ModelDescriptor descriptor, AbortSignal signal) {
            return Stream.empty();
        }
    }
}
