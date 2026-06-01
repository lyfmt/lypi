package cn.lypi.ai;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.model.ApiStyle;
import cn.lypi.contracts.model.CostProfile;
import cn.lypi.contracts.model.ModelDescriptor;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultModelRegistryTest {
    @Test
    void listReturnsDescriptorsInRegistrationOrder() {
        ModelDescriptor openAi = descriptor("openai", "gpt-5");
        ModelDescriptor anthropic = descriptor("anthropic", "claude-4");
        ModelRegistry registry = new DefaultModelRegistry(List.of(openAi, anthropic));

        assertThat(registry.list()).containsExactly(openAi, anthropic);
    }

    @Test
    void findMatchesProviderAndModelId() {
        ModelDescriptor descriptor = descriptor("openai", "gpt-5");
        ModelRegistry registry = new DefaultModelRegistry(List.of(descriptor));

        assertThat(registry.find(new ModelSelection("openai", "gpt-5", ThinkingLevel.MEDIUM)))
            .contains(descriptor);
    }

    @Test
    void findReturnsEmptyWhenSelectionIsUnavailable() {
        ModelRegistry registry = new DefaultModelRegistry(List.of(descriptor("openai", "gpt-5")));

        assertThat(registry.find(new ModelSelection("openai", "gpt-4.1", ThinkingLevel.MEDIUM)))
            .isEmpty();
    }

    @Test
    void registryDefensivelyCopiesDescriptors() {
        ModelDescriptor descriptor = descriptor("openai", "gpt-5");
        List<ModelDescriptor> descriptors = new java.util.ArrayList<>(List.of(descriptor));
        ModelRegistry registry = new DefaultModelRegistry(descriptors);

        descriptors.clear();

        assertThat(registry.list()).containsExactly(descriptor);
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
            new CostProfile(BigDecimal.ONE, BigDecimal.TEN, "USD"),
            Map.of("profile", "test")
        );
    }
}
