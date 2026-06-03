package cn.lypi.boot.ai;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.ai.ModelPort;
import cn.lypi.ai.ModelRegistry;
import cn.lypi.ai.provider.openai.OpenAiCompatibleProviderAdapter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LyPiAiAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(LyPiAiAutoConfiguration.class)
        .withPropertyValues(
            "lypi.ai.default-provider=openai",
            "lypi.ai.default-model=gpt-5-mini",
            "lypi.ai.providers.openai.enabled=true",
            "lypi.ai.providers.openai.api-style=openai_compatible",
            "lypi.ai.providers.openai.request-style=responses",
            "lypi.ai.providers.openai.fallback-request-style=chat_completions",
            "lypi.ai.providers.openai.transport=auto",
            "lypi.ai.providers.openai.base-url=https://api.openai.test/v1",
            "lypi.ai.providers.openai.websocket-path=/v1/responses",
            "lypi.ai.providers.openai.api-key=${LYPI_TEST_TOKEN}",
            "lypi.ai.providers.openai.models[0].model-id=gpt-5-mini",
            "lypi.ai.providers.openai.models[0].context-window=128000",
            "lypi.ai.providers.openai.models[0].max-output-tokens=16384",
            "lypi.ai.providers.openai.models[0].supports-thinking=true",
            "lypi.ai.providers.openai.models[0].supports-image-input=true",
            "lypi.ai.providers.openai.models[0].input-token-cost=0",
            "lypi.ai.providers.openai.models[0].output-token-cost=0",
            "lypi.ai.providers.openai.models[0].currency=USD",
            "lypi.ai.providers.disabled.enabled=false",
            "lypi.ai.providers.disabled.base-url=https://disabled.test/v1",
            "lypi.ai.providers.disabled.api-key=disabled-secret",
            "lypi.ai.providers.disabled.models[0].model-id=disabled-model"
        );

    @Test
    void createsModelPortRegistryAndOpenAiAdapterFromProperties() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ModelPort.class);
            assertThat(context).hasSingleBean(ModelRegistry.class);
            ModelRegistry registry = context.getBean(ModelRegistry.class);
            List<?> adapters = context.getBean("openAiCompatibleProviderAdapters", List.class);

            assertThat(registry.list()).hasSize(1);
            assertThat(registry.list().getFirst().provider()).isEqualTo("openai");
            assertThat(registry.list().getFirst().modelId()).isEqualTo("gpt-5-mini");
            assertThat(registry.list().getFirst().compat().toString()).doesNotContain("LYPI_TEST_TOKEN");
            assertThat(adapters).hasSize(1);
            assertThat(adapters.getFirst()).isInstanceOf(OpenAiCompatibleProviderAdapter.class);
        });
    }

    @Test
    void loadsWithEmptyPlaceholderConfiguration() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiAiAutoConfiguration.class)
            .run(context -> {
                assertThat(context).hasSingleBean(ModelRegistry.class);
                assertThat(context.getBean(ModelRegistry.class).list()).isEmpty();
                assertThat(context).doesNotHaveBean(OpenAiCompatibleProviderAdapter.class);
            });
    }

    @Test
    void bindsProviderPropertiesFromYamlResources() {
        new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(LyPiAiAutoConfiguration.class)
            .withPropertyValues("spring.config.name=application-test")
            .run(context -> {
                ModelRegistry registry = context.getBean(ModelRegistry.class);

                assertThat(registry.list()).hasSize(1);
                assertThat(registry.list().getFirst().provider()).isEqualTo("openai");
                assertThat(registry.list().getFirst().baseUrl().toString()).isEqualTo("https://api.openai.test/v1");
                assertThat(registry.list().getFirst().supportsThinking()).isTrue();
            });
    }
}
