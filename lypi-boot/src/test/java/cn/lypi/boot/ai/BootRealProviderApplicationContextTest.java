package cn.lypi.boot.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cn.lypi.ai.ApiProviderRegistry;
import cn.lypi.ai.ModelPort;
import cn.lypi.ai.ModelRegistry;
import cn.lypi.ai.ProviderAdapter;
import cn.lypi.ai.provider.openai.OpenAiCompatibleProviderAdapter;
import cn.lypi.agent.compact.AiCompactionSummarizer;
import cn.lypi.agent.compact.CompactionSummarizer;
import cn.lypi.contracts.model.ModelDescriptor;
import cn.lypi.contracts.model.ThinkingLevel;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class BootRealProviderApplicationContextTest {
    @Test
    void startsApplicationContextWithConfiguredRealProviderBeans() {
        assumeTrue(
            Boolean.getBoolean("lypi.boot.real-provider.e2e") || Boolean.getBoolean("lypi.provider.e2e"),
            "Enable with -Dlypi.provider.e2e=true or -Dlypi.boot.real-provider.e2e=true"
        );
        RealProviderSettings settings = RealProviderSettings.fromSystemProperties();

        new ApplicationContextRunner()
            .withUserConfiguration(LyPiAiAutoConfiguration.class)
            .withPropertyValues(
                "lypi.ai.default-provider=" + settings.provider(),
                "lypi.ai.default-model=" + settings.model(),
                "lypi.ai.compaction-summary.enabled=true",
                "lypi.ai.providers." + settings.provider() + ".enabled=true",
                "lypi.ai.providers." + settings.provider() + ".api-style=openai_compatible",
                "lypi.ai.providers." + settings.provider() + ".request-style=responses",
                "lypi.ai.providers." + settings.provider() + ".fallback-request-style=responses",
                "lypi.ai.providers." + settings.provider() + ".transport=sse",
                "lypi.ai.providers." + settings.provider() + ".base-url=" + settings.baseUrl(),
                "lypi.ai.providers." + settings.provider() + ".api-key=" + settings.apiKey(),
                "lypi.ai.providers." + settings.provider() + ".models[0].model-id=" + settings.model(),
                "lypi.ai.providers." + settings.provider() + ".models[0].context-window=128000",
                "lypi.ai.providers." + settings.provider() + ".models[0].max-output-tokens=16384",
                "lypi.ai.providers." + settings.provider() + ".models[0].supports-thinking=true",
                "lypi.ai.providers." + settings.provider() + ".models[0].compat.thinking=" + settings.thinkingLevel().name().toLowerCase(java.util.Locale.ROOT)
            )
            .run(context -> {
                assertThat(context).hasSingleBean(ModelPort.class);
                assertThat(context).hasSingleBean(ModelRegistry.class);
                assertThat(context).hasSingleBean(ApiProviderRegistry.class);
                assertThat(context).hasSingleBean(CompactionSummarizer.class);
                assertThat(context.getBean(CompactionSummarizer.class)).isInstanceOf(AiCompactionSummarizer.class);
                List<?> adapters = context.getBean("openAiCompatibleProviderAdapters", List.class);
                assertThat(adapters).singleElement().isInstanceOf(OpenAiCompatibleProviderAdapter.class);
                assertThat(adapters.getFirst()).isInstanceOf(ProviderAdapter.class);
                ModelDescriptor descriptor = context.getBean(ModelRegistry.class).list().stream()
                    .filter(candidate -> candidate.provider().equals(settings.provider()))
                    .findFirst()
                    .orElseThrow();
                assertThat(descriptor.modelId()).isEqualTo(settings.model());
                assertThat(descriptor.baseUrl()).isEqualTo(settings.baseUrl());
                assertThat(descriptor.supportsThinking()).isTrue();
                assertThat(descriptor.compat()).containsEntry("thinking", settings.thinkingLevel().name().toLowerCase(java.util.Locale.ROOT));
                assertThat(descriptor.compat().toString()).doesNotContain(settings.apiKey());
            });
    }

    private record RealProviderSettings(
        URI baseUrl,
        String apiKey,
        String provider,
        String model,
        ThinkingLevel thinkingLevel
    ) {
        private static RealProviderSettings fromSystemProperties() {
            return new RealProviderSettings(
                requiredUri("lypi.provider.e2e.base-url"),
                required("lypi.provider.e2e.api-key"),
                System.getProperty("lypi.provider.e2e.provider", "real-provider"),
                required("lypi.provider.e2e.model"),
                ThinkingLevel.valueOf(System.getProperty("lypi.provider.e2e.thinking", "OFF").toUpperCase(java.util.Locale.ROOT))
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
