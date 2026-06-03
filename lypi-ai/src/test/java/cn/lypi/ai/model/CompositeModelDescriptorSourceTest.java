package cn.lypi.ai.model;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.model.ApiStyle;
import cn.lypi.contracts.model.CostProfile;
import cn.lypi.contracts.model.ModelDescriptor;
import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CompositeModelDescriptorSourceTest {
    @Test
    void mergesSourcesInOrderAndOverridesSameProviderModel() {
        ModelDescriptor builtin = descriptor("openai", "gpt-5-mini", 128_000);
        ModelDescriptor configured = descriptor("openai", "gpt-5-mini", 256_000);
        ModelDescriptor remote = descriptor("test-provider", "remote-model", 64_000);
        CompositeModelDescriptorSource source = new CompositeModelDescriptorSource(List.of(
            new StaticModelDescriptorSource(List.of(builtin)),
            new StaticModelDescriptorSource(List.of(configured, remote))
        ));

        assertThat(source.list())
            .extracting(ModelDescriptor::provider, ModelDescriptor::modelId, ModelDescriptor::contextWindow)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("openai", "gpt-5-mini", 256_000),
                org.assertj.core.groups.Tuple.tuple("test-provider", "remote-model", 64_000)
            );
    }

    @Test
    void keepsFirstInsertionOrderWhenLaterSourceOverrides() {
        ModelDescriptor first = descriptor("openai", "gpt-5-mini", 128_000);
        ModelDescriptor second = descriptor("other", "model-a", 32_000);
        ModelDescriptor override = descriptor("openai", "gpt-5-mini", 256_000);
        CompositeModelDescriptorSource source = new CompositeModelDescriptorSource(List.of(
            new StaticModelDescriptorSource(List.of(first, second)),
            new StaticModelDescriptorSource(List.of(override))
        ));

        assertThat(source.list())
            .extracting(ModelDescriptor::provider, ModelDescriptor::modelId, ModelDescriptor::contextWindow)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("openai", "gpt-5-mini", 256_000),
                org.assertj.core.groups.Tuple.tuple("other", "model-a", 32_000)
            );
    }

    @Test
    void defensivelyCopiesSourceLists() {
        List<ModelDescriptor> descriptors = new ArrayList<>();
        descriptors.add(descriptor("openai", "gpt-5-mini", 128_000));
        StaticModelDescriptorSource staticSource = new StaticModelDescriptorSource(descriptors);
        descriptors.clear();

        List<ModelDescriptor> listed = staticSource.list();
        listed.clear();

        assertThat(staticSource.list())
            .extracting(ModelDescriptor::modelId)
            .containsExactly("gpt-5-mini");
    }

    private static ModelDescriptor descriptor(String provider, String modelId, int contextWindow) {
        return new ModelDescriptor(
            provider,
            modelId,
            URI.create("https://example.test/v1"),
            ApiStyle.OPENAI_COMPATIBLE,
            contextWindow,
            16_384,
            true,
            false,
            new CostProfile(BigDecimal.ZERO, BigDecimal.ZERO, "USD"),
            Map.of()
        );
    }
}
