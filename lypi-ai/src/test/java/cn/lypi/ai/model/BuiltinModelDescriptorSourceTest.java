package cn.lypi.ai.model;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.model.ApiStyle;
import cn.lypi.contracts.model.ModelDescriptor;
import java.util.Locale;
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

    private static String normalized(String key) {
        return key.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
    }
}
