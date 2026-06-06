package cn.lypi.boot.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lypi.ai.ApiProviderRegistry;
import cn.lypi.ai.ModelPort;
import cn.lypi.ai.ModelRegistry;
import cn.lypi.ai.model.RemoteModelDiscoveryClient;
import cn.lypi.ai.provider.RequestStyle;
import cn.lypi.ai.provider.openai.OpenAiCompatibleProviderAdapter;
import cn.lypi.agent.compact.AiCompactionSummarizer;
import cn.lypi.agent.compact.CompactSummaryRequest;
import cn.lypi.agent.compact.CompactionSummarizer;
import cn.lypi.agent.compact.CompactionSummaryFallbackPolicy;
import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.session.CompactionKind;
import cn.lypi.contracts.session.CompactionPlan;
import java.net.URI;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
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
            "lypi.ai.providers.openai.compat.api_key=${LYPI_COMPAT_TOKEN}",
            "lypi.ai.providers.openai.compat.Authorization=${LYPI_AUTH_TOKEN}",
            "lypi.ai.providers.openai.compat.client-secret=${LYPI_CLIENT_SECRET}",
            "lypi.ai.providers.openai.compat.x-api-key=${LYPI_X_API_KEY}",
            "lypi.ai.providers.openai.compat.safe-flag=true",
            "lypi.ai.providers.openai.models[0].model-id=gpt-5-mini",
            "lypi.ai.providers.openai.models[0].context-window=256000",
            "lypi.ai.providers.openai.models[0].max-output-tokens=16384",
            "lypi.ai.providers.openai.models[0].supports-thinking=true",
            "lypi.ai.providers.openai.models[0].supports-image-input=true",
            "lypi.ai.providers.openai.models[0].input-token-cost=0",
            "lypi.ai.providers.openai.models[0].output-token-cost=0",
            "lypi.ai.providers.openai.models[0].currency=USD",
            "lypi.ai.providers.openai.models[0].compat.access_token=${LYPI_ACCESS_TOKEN}",
            "lypi.ai.providers.openai.models[0].compat.refresh_token=${LYPI_REFRESH_TOKEN}",
            "lypi.ai.providers.openai.models[0].compat.secret_key=${LYPI_SECRET_KEY}",
            "lypi.ai.providers.openai.models[0].compat.vendor=fixture",
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
            assertThat(context).hasSingleBean(ApiProviderRegistry.class);
            ModelRegistry registry = context.getBean(ModelRegistry.class);
            List<?> adapters = context.getBean("openAiCompatibleProviderAdapters", List.class);

            assertThat(registry.list()).hasSize(1);
            assertThat(registry.list().getFirst().provider()).isEqualTo("openai");
            assertThat(registry.list().getFirst().modelId()).isEqualTo("gpt-5-mini");
            assertThat(registry.list().getFirst().contextWindow()).isEqualTo(256000);
            assertThat(registry.list().getFirst().compat().toString()).doesNotContain("LYPI_TEST_TOKEN");
            assertThat(registry.list().getFirst().compat()).containsEntry("safe-flag", "true");
            assertThat(registry.list().getFirst().compat()).containsEntry("vendor", "fixture");
            assertThat(registry.list().getFirst().compat().toString())
                .doesNotContain(
                    "LYPI_COMPAT_TOKEN",
                    "LYPI_AUTH_TOKEN",
                    "LYPI_CLIENT_SECRET",
                    "LYPI_X_API_KEY",
                    "LYPI_ACCESS_TOKEN",
                    "LYPI_REFRESH_TOKEN",
                    "LYPI_SECRET_KEY"
                );
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
                assertThat(context.getBean(ModelRegistry.class).list()).isNotEmpty();
                assertThat(context).doesNotHaveBean(OpenAiCompatibleProviderAdapter.class);
            });
    }

    @Test
    void doesNotTriggerRemoteDiscoveryWhenDisabled() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiAiAutoConfiguration.class)
            .withBean(RemoteModelDiscoveryClient.class, ThrowingRemoteModelDiscoveryClient::new)
            .withPropertyValues(
                "lypi.ai.providers.openai.enabled=true",
                "lypi.ai.providers.openai.api-style=openai_compatible",
                "lypi.ai.providers.openai.base-url=https://api.openai.test/v1",
                "lypi.ai.providers.openai.api-key=${LYPI_TEST_TOKEN}",
                "lypi.ai.providers.openai.model-discovery.enabled=false"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(ModelRegistry.class);
                assertThat(context.getBean(ModelRegistry.class).list()).isNotEmpty();
            });
    }

    @Test
    void defaultsResponsesFallbackToResponses() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiAiAutoConfiguration.class)
            .withPropertyValues(
                "lypi.ai.providers.openai.enabled=true",
                "lypi.ai.providers.openai.base-url=https://api.openai.test/v1",
                "lypi.ai.providers.openai.api-key=${LYPI_TEST_TOKEN}"
            )
            .run(context -> {
                LyPiAiProperties properties = context.getBean(LyPiAiProperties.class);

                assertThat(properties.getProviders().get("openai").getRequestStyle()).isEqualTo(RequestStyle.RESPONSES);
                assertThat(properties.getProviders().get("openai").getFallbackRequestStyle()).isEqualTo(RequestStyle.RESPONSES);
            });
    }

    @Test
    void bindsCompactionSummaryPropertiesWithoutSummaryModelOverride() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiAiAutoConfiguration.class)
            .withPropertyValues(
                "lypi.ai.compaction-summary.enabled=true",
                "lypi.ai.compaction-summary.fallback-policy=skip_compaction"
            )
            .run(context -> {
                LyPiAiProperties properties = context.getBean(LyPiAiProperties.class);

                assertThat(properties.getCompactionSummary().isEnabled()).isTrue();
                assertThat(properties.getCompactionSummary().getFallbackPolicy())
                    .isEqualTo(CompactionSummaryFallbackPolicy.SKIP_COMPACTION);
            });
    }

    @Test
    void createsUnavailableCompactionSummarizerWhenSummaryDisabled() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiAiAutoConfiguration.class)
            .run(context -> {
                assertThat(context).hasSingleBean(CompactionSummarizer.class);
                assertThat(context.getBean(CompactionSummarizer.class))
                    .isNotInstanceOf(AiCompactionSummarizer.class);
                assertThatThrownBy(() -> context.getBean(CompactionSummarizer.class).summarize(disabledSummaryRequest()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("disabled");
            });
    }

    @Test
    void createsAiCompactionSummarizerWhenSummaryEnabled() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiAiAutoConfiguration.class)
            .withPropertyValues("lypi.ai.compaction-summary.enabled=true")
            .run(context -> {
                assertThat(context).hasSingleBean(CompactionSummarizer.class);
                assertThat(context.getBean(CompactionSummarizer.class))
                    .isInstanceOf(AiCompactionSummarizer.class);
            });
    }

    @Test
    void supportsMultipleOpenAiCompatibleProvidersWithOneApiProvider() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiAiAutoConfiguration.class)
            .withPropertyValues(
                "lypi.ai.providers.openai.enabled=true",
                "lypi.ai.providers.openai.api-style=openai_compatible",
                "lypi.ai.providers.openai.base-url=https://api.openai.test/v1",
                "lypi.ai.providers.openai.api-key=${LYPI_TEST_TOKEN}",
                "lypi.ai.providers.openai.models[0].model-id=gpt-5-mini",
                "lypi.ai.providers.openai.models[0].context-window=128000",
                "lypi.ai.providers.openai.models[0].max-output-tokens=16384",
                "lypi.ai.providers.fixture.enabled=true",
                "lypi.ai.providers.fixture.api-style=openai_compatible",
                "lypi.ai.providers.fixture.base-url=https://api.fixture.test/v1",
                "lypi.ai.providers.fixture.api-key=${LYPI_FIXTURE_TOKEN}",
                "lypi.ai.providers.fixture.models[0].model-id=fixture-model",
                "lypi.ai.providers.fixture.models[0].context-window=64000",
                "lypi.ai.providers.fixture.models[0].max-output-tokens=8192"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(ApiProviderRegistry.class);
                assertThat(context.getBean(ModelRegistry.class).list())
                    .extracting(descriptor -> descriptor.provider() + ":" + descriptor.modelId())
                    .contains("openai:gpt-5-mini", "fixture:fixture-model");
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

    private static final class ThrowingRemoteModelDiscoveryClient extends RemoteModelDiscoveryClient {
        @Override
        public List<String> discover(URI baseUrl, String apiKey, List<String> paths, Duration timeout) {
            throw new AssertionError("Remote discovery should not be called when disabled.");
        }
    }

    private static CompactSummaryRequest disabledSummaryRequest() {
        ContextSnapshot context = new ContextSnapshot(
            new SystemPrompt("system", List.of("test"), "hash"),
            List.of(),
            new ModelSelection("test", "gpt-test", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE,
            new ContextBudget(0, 128000, 100000, 8192, 16384, 0, 0, BigDecimal.ZERO)
        );
        CompactionPlan plan = new CompactionPlan("first", "kept", List.of("first"), CompactionKind.SESSION);
        AbortSignal abortSignal = () -> false;
        return new CompactSummaryRequest(context, plan, List.of(), abortSignal);
    }
}
