package cn.lypi.ai.model;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.model.ApiStyle;
import cn.lypi.contracts.model.ModelDescriptor;
import java.math.BigDecimal;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class BuiltinModelDescriptorSourceTest {
    @Test
    void listsAtLeastOneOpenAiCompatibleModel() {
        assertThat(new BuiltinModelDescriptorSource().list())
            .anySatisfy(descriptor -> {
                assertThat(descriptor.provider()).isEqualTo("openai");
                assertThat(descriptor.modelId()).isNotBlank();
                assertThat(descriptor.apiStyle()).isEqualTo(ApiStyle.OPENAI_COMPATIBLE);
            });
    }

    @Test
    void builtinDescriptorsDoNotContainSecretCompatKeys() {
        assertThat(new BuiltinModelDescriptorSource().list())
            .flatExtracting(descriptor -> descriptor.compat().keySet())
            .noneSatisfy(key -> assertThat(normalized(key))
                .isIn("apikey", "authorization", "accesstoken", "bearertoken", "token"));
    }

    @Test
    void builtinOpenAiModelFieldsAreComplete() {
        ModelDescriptor descriptor = new BuiltinModelDescriptorSource().list().getFirst();

        assertThat(descriptor.provider()).isNotBlank();
        assertThat(descriptor.modelId()).isNotBlank();
        assertThat(descriptor.baseUrl()).isNotNull();
        assertThat(descriptor.apiStyle()).isNotNull();
        assertThat(descriptor.contextWindow()).isPositive();
        assertThat(descriptor.maxOutputTokens()).isPositive();
        assertThat(descriptor.costProfile()).isNotNull();
        assertThat(descriptor.compat()).isNotNull();
    }

    @Test
    void listsCurrentOfficialOpenAiModels() {
        Map<String, ModelDescriptor> descriptors = new BuiltinModelDescriptorSource().list().stream()
            .filter(descriptor -> descriptor.provider().equals("openai"))
            .collect(Collectors.toMap(ModelDescriptor::modelId, Function.identity()));

        assertOpenAiModel(descriptors.get("gpt-5.5"), 1_050_000, 128_000, "5.00", "30.00");
        assertOpenAiModel(descriptors.get("gpt-5.4"), 1_050_000, 128_000, "2.50", "15.00");
        assertOpenAiModel(descriptors.get("gpt-5.4-mini"), 400_000, 128_000, "0.75", "4.50");
    }

    private static void assertOpenAiModel(
        ModelDescriptor descriptor,
        int contextWindow,
        int maxOutputTokens,
        String inputTokenCost,
        String outputTokenCost
    ) {
        assertThat(descriptor).isNotNull();
        assertThat(descriptor.provider()).isEqualTo("openai");
        assertThat(descriptor.baseUrl()).isEqualTo(URI.create("https://api.openai.com/v1"));
        assertThat(descriptor.apiStyle()).isEqualTo(ApiStyle.OPENAI_COMPATIBLE);
        assertThat(descriptor.contextWindow()).isEqualTo(contextWindow);
        assertThat(descriptor.maxOutputTokens()).isEqualTo(maxOutputTokens);
        assertThat(descriptor.supportsThinking()).isTrue();
        assertThat(descriptor.supportsImageInput()).isTrue();
        assertThat(descriptor.costProfile().inputTokenCost()).isEqualByComparingTo(new BigDecimal(inputTokenCost));
        assertThat(descriptor.costProfile().outputTokenCost()).isEqualByComparingTo(new BigDecimal(outputTokenCost));
        assertThat(descriptor.costProfile().currency()).isEqualTo("USD");
        assertThat(descriptor.compat()).isEmpty();
    }

    private static String normalized(String key) {
        return key.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
    }
}
