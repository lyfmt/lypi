package cn.lycode.boot.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lycode.ai.ApiProviderRegistry;
import cn.lycode.ai.ModelPort;
import cn.lycode.ai.ModelRegistry;
import cn.lycode.ai.ProviderAdapterApiProvider;
import cn.lycode.ai.RuntimeModelRegistry;
import cn.lycode.ai.model.DiscoveredModel;
import cn.lycode.ai.model.RemoteModelDiscoveryClient;
import cn.lycode.ai.provider.RequestStyle;
import cn.lycode.ai.provider.TransportMode;
import cn.lycode.ai.provider.anthropic.AnthropicCompatibleProviderAdapter;
import cn.lycode.ai.provider.anthropic.AnthropicProviderConfig;
import cn.lycode.ai.provider.openai.OpenAiCompatibleProviderAdapter;
import cn.lycode.ai.provider.openai.OpenAiProviderConfig;
import cn.lycode.agent.compact.AiCompactionSummarizer;
import cn.lycode.agent.compact.CompactionSummarizer;
import cn.lycode.agent.compact.CompactionSummaryFallbackPolicy;
import cn.lycode.contracts.error.ErrorSeverity;
import cn.lycode.contracts.error.ModelProviderException;
import cn.lycode.contracts.model.ModelDescriptor;
import cn.lycode.contracts.runtime.ProviderLoginPort;
import cn.lycode.contracts.runtime.ProviderLoginResult;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LyCodeAiAutoConfigurationTest {
    @TempDir
    Path tempDir;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(LyCodeAiAutoConfiguration.class)
        .withPropertyValues(
            "lycode.ai.default-provider=openai",
            "lycode.ai.default-model=gpt-5-mini",
            "lycode.ai.providers.openai.enabled=true",
            "lycode.ai.providers.openai.api-style=openai_compatible",
            "lycode.ai.providers.openai.request-style=responses",
            "lycode.ai.providers.openai.fallback-request-style=chat_completions",
            "lycode.ai.providers.openai.transport=auto",
            "lycode.ai.providers.openai.base-url=https://api.openai.test/v1",
            "lycode.ai.providers.openai.websocket-path=/v1/responses",
            "lycode.ai.providers.openai.api-key=${LYCODE_TEST_TOKEN}",
            "lycode.ai.providers.openai.compat.api_key=${LYCODE_COMPAT_TOKEN}",
            "lycode.ai.providers.openai.compat.Authorization=${LYCODE_AUTH_TOKEN}",
            "lycode.ai.providers.openai.compat.client-secret=${LYCODE_CLIENT_SECRET}",
            "lycode.ai.providers.openai.compat.x-api-key=${LYCODE_X_API_KEY}",
            "lycode.ai.providers.openai.compat.safe-flag=true",
            "lycode.ai.providers.openai.models[0].model-id=gpt-5-mini",
            "lycode.ai.providers.openai.models[0].context-window=256000",
            "lycode.ai.providers.openai.models[0].max-output-tokens=16384",
            "lycode.ai.providers.openai.models[0].supports-thinking=true",
            "lycode.ai.providers.openai.models[0].supports-image-input=true",
            "lycode.ai.providers.openai.models[0].input-token-cost=0",
            "lycode.ai.providers.openai.models[0].output-token-cost=0",
            "lycode.ai.providers.openai.models[0].currency=USD",
            "lycode.ai.providers.openai.models[0].compat.access_token=${LYCODE_ACCESS_TOKEN}",
            "lycode.ai.providers.openai.models[0].compat.refresh_token=${LYCODE_REFRESH_TOKEN}",
            "lycode.ai.providers.openai.models[0].compat.secret_key=${LYCODE_SECRET_KEY}",
            "lycode.ai.providers.openai.models[0].compat.vendor=fixture",
            "lycode.ai.providers.disabled.enabled=false",
            "lycode.ai.providers.disabled.base-url=https://disabled.test/v1",
            "lycode.ai.providers.disabled.api-key=disabled-secret",
            "lycode.ai.providers.disabled.models[0].model-id=disabled-model"
        );

    @Test
    void createsModelPortRegistryAndOpenAiAdapterFromProperties() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ModelPort.class);
            assertThat(context).hasSingleBean(ModelRegistry.class);
            assertThat(context).hasSingleBean(ApiProviderRegistry.class);
            ModelRegistry registry = context.getBean(ModelRegistry.class);
            List<?> adapters = context.getBean("openAiCompatibleProviderAdapters", List.class);
            ModelDescriptor descriptor = openAiModel(registry, "gpt-5-mini");

            assertThat(registry.list())
                .extracting(ModelDescriptor::modelId)
                .contains("gpt-5.5", "gpt-5.4", "gpt-5.4-mini", "gpt-5-mini");
            assertThat(descriptor.provider()).isEqualTo("openai");
            assertThat(descriptor.contextWindow()).isEqualTo(256000);
            assertThat(descriptor.compat().toString()).doesNotContain("LYCODE_TEST_TOKEN");
            assertThat(descriptor.compat()).containsEntry("safe-flag", "true");
            assertThat(descriptor.compat()).containsEntry("vendor", "fixture");
            assertThat(descriptor.compat().toString())
                .doesNotContain(
                    "LYCODE_COMPAT_TOKEN",
                    "LYCODE_AUTH_TOKEN",
                    "LYCODE_CLIENT_SECRET",
                    "LYCODE_X_API_KEY",
                    "LYCODE_ACCESS_TOKEN",
                    "LYCODE_REFRESH_TOKEN",
                    "LYCODE_SECRET_KEY"
                );
            assertThat(adapters).hasSize(1);
            assertThat(adapters.getFirst()).isInstanceOf(OpenAiCompatibleProviderAdapter.class);
        });
    }

    @Test
    void loadsWithEmptyPlaceholderConfiguration() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeAiAutoConfiguration.class)
            .run(context -> {
                assertThat(context).hasSingleBean(ModelRegistry.class);
                assertThat(context.getBean(ModelRegistry.class).list()).isNotEmpty();
                assertThat(context.getBean("openAiCompatibleProviderAdapters", List.class)).hasSize(1);
                assertThat(context.getBean(ApiProviderRegistry.class).find(cn.lycode.contracts.model.ApiStyle.OPENAI_COMPATIBLE))
                    .isPresent();
            });
    }

    @Test
    void explicitOpenAiProviderConfigurationOverridesBuiltInAdapter() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeAiAutoConfiguration.class)
            .withPropertyValues(
                "lycode.ai.providers.openai.base-url=https://api.openai.test/v1",
                "lycode.ai.providers.openai.api-key=fixture-token"
            )
            .run(context -> {
                List<?> adapters = context.getBean("openAiCompatibleProviderAdapters", List.class);
                OpenAiCompatibleProviderAdapter adapter = (OpenAiCompatibleProviderAdapter) adapters.getFirst();

                assertThat(adapters).hasSize(1);
                assertThat(config(adapter).baseUrl()).hasToString("https://api.openai.test/v1");
                assertThat(config(adapter).apiKey()).isEqualTo("fixture-token");
                assertThat(config(adapter).fallbackRequestStyle()).isEqualTo(RequestStyle.CHAT_COMPLETIONS);
                assertThat(context.getBean(ModelRegistry.class).list())
                    .filteredOn(descriptor -> descriptor.provider().equals("openai"))
                    .allSatisfy(descriptor ->
                        assertThat(descriptor.baseUrl()).hasToString("https://api.openai.test/v1"));
            });
    }

    @Test
    void configuredOpenAiModelOverridesBuiltInDescriptorWithoutRepeatingProviderTransportFields() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeAiAutoConfiguration.class)
            .withPropertyValues(
                "lycode.ai.providers.openai.enabled=true",
                "lycode.ai.providers.openai.models[0].model-id=gpt-5-mini",
                "lycode.ai.providers.openai.models[0].context-window=256000",
                "lycode.ai.providers.openai.models[0].max-output-tokens=32768"
            )
            .run(context -> {
                ModelDescriptor descriptor = openAiModel(context.getBean(ModelRegistry.class), "gpt-5-mini");

                assertThat(descriptor.provider()).isEqualTo("openai");
                assertThat(descriptor.baseUrl()).hasToString("https://api.openai.com/v1");
                assertThat(descriptor.contextWindow()).isEqualTo(256000);
                assertThat(descriptor.maxOutputTokens()).isEqualTo(32768);
            });
    }

    @Test
    void explicitOpenAiProviderDisableRemovesBuiltInAdapter() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeAiAutoConfiguration.class)
            .withPropertyValues("lycode.ai.providers.openai.enabled=false")
            .run(context -> {
                assertThat(context.getBean("openAiCompatibleProviderAdapters", List.class)).isEmpty();
                assertThat(context.getBean(ModelRegistry.class).list()).isEmpty();
            });
    }

    @Test
    void exposesMutableOpenAiDispatcherEvenWithoutInitialOpenAiAdapter() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeAiAutoConfiguration.class)
            .withPropertyValues("lycode.ai.providers.openai.enabled=false")
            .run(context -> {
                assertThat(context).hasSingleBean(ProviderAdapterApiProvider.class);
                assertThat(context).hasSingleBean(RuntimeModelRegistry.class);
                assertThat(context).hasSingleBean(ProviderLoginPort.class);
                assertThat(context.getBean("openAiCompatibleProviderAdapters", List.class)).isEmpty();
                assertThat(context.getBean(ApiProviderRegistry.class)
                    .find(cn.lycode.contracts.model.ApiStyle.OPENAI_COMPATIBLE)).isPresent();
            });
    }

    @Test
    void exposesUnavailableLoginPortWhenModelRegistryIsReplacedWithoutRuntimeMutationSupport() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeAiAutoConfiguration.class)
            .withBean(ModelRegistry.class, () -> new ModelRegistry() {
                @Override
                public List<ModelDescriptor> list() {
                    return List.of();
                }

                @Override
                public java.util.Optional<ModelDescriptor> find(cn.lycode.contracts.model.ModelSelection selection) {
                    return java.util.Optional.empty();
                }
            })
            .run(context -> assertThatThrownBy(() -> context.getBean(ProviderLoginPort.class)
                .register("zen", "https://example.test/v1", "fixture-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("provider login is unavailable"));
    }

    @Test
    void importsManagedLoginProviderAndDiscoversItsModelsAtStartup() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("home"));
        Path configRoot = Files.createDirectories(home.resolve(".ly-code"));
        Files.writeString(configRoot.resolve("login-providers.properties"), """
            lycode.ai.providers.login-fixture.enabled=true
            lycode.ai.providers.login-fixture.api-style=openai_compatible
            lycode.ai.providers.login-fixture.base-url=https://fixture.test/v1
            lycode.ai.providers.login-fixture.api-key=fixture-login-key
            lycode.ai.providers.login-fixture.request-style=chat_completions
            lycode.ai.providers.login-fixture.fallback-request-style=chat_completions
            lycode.ai.providers.login-fixture.transport=sse
            lycode.ai.providers.login-fixture.model-discovery.enabled=true
            lycode.ai.providers.login-fixture.model-discovery.paths[0]=/models
            lycode.ai.providers.login-fixture.model-discovery.paths[1]=/model
            lycode.ai.providers.login-fixture.compat.requires-reasoning-content-on-assistant-messages=true
            """);

        new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(LyCodeAiAutoConfiguration.class)
            .withBean(RemoteModelDiscoveryClient.class, () -> new FixedRemoteModelDiscoveryClient("discovered-login-model"))
            .withSystemProperties("user.home=" + home)
            .run(context -> {
                ModelDescriptor descriptor = model(
                    context.getBean(ModelRegistry.class),
                    "login-fixture",
                    "discovered-login-model"
                );

                assertThat(descriptor.baseUrl()).hasToString("https://fixture.test/v1");
                assertThat(context.getBean(ProviderLoginPort.class)).isNotNull();
                assertThat(context.getBean(ApiProviderRegistry.class)
                    .find(cn.lycode.contracts.model.ApiStyle.OPENAI_COMPATIBLE)).isPresent();
                List<?> adapters = context.getBean("openAiCompatibleProviderAdapters", List.class);
                OpenAiCompatibleProviderAdapter adapter = adapters.stream()
                    .map(OpenAiCompatibleProviderAdapter.class::cast)
                    .filter(candidate -> config(candidate).provider().equals("login-fixture"))
                    .findFirst()
                    .orElseThrow();
                assertThat(config(adapter).compat().get("requires-reasoning-content-on-assistant-messages"))
                    .isIn(true, "true");
            });
    }

    @Test
    void doesNotTriggerRemoteDiscoveryWhenDisabled() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeAiAutoConfiguration.class)
            .withBean(RemoteModelDiscoveryClient.class, ThrowingRemoteModelDiscoveryClient::new)
            .withPropertyValues(
                "lycode.ai.providers.openai.enabled=true",
                "lycode.ai.providers.openai.api-style=openai_compatible",
                "lycode.ai.providers.openai.base-url=https://api.openai.test/v1",
                "lycode.ai.providers.openai.api-key=${LYCODE_TEST_TOKEN}",
                "lycode.ai.providers.openai.model-discovery.enabled=false"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(ModelRegistry.class);
                assertThat(context.getBean(ModelRegistry.class).list()).isNotEmpty();
            });
    }

    @Test
    void configuredModelDescriptorOverridesRemoteAndBuiltInDescriptors() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeAiAutoConfiguration.class)
            .withBean(RemoteModelDiscoveryClient.class, () -> new FixedRemoteModelDiscoveryClient("gpt-5-mini"))
            .withPropertyValues(
                "lycode.ai.providers.openai.enabled=true",
                "lycode.ai.providers.openai.api-style=openai_compatible",
                "lycode.ai.providers.openai.base-url=https://api.openai.test/v1",
                "lycode.ai.providers.openai.model-discovery.enabled=true",
                "lycode.ai.providers.openai.models[0].model-id=remote-defaults",
                "lycode.ai.providers.openai.models[0].context-window=96000",
                "lycode.ai.providers.openai.models[0].max-output-tokens=8192",
                "lycode.ai.providers.openai.models[1].model-id=gpt-5-mini",
                "lycode.ai.providers.openai.models[1].context-window=64000",
                "lycode.ai.providers.openai.models[1].max-output-tokens=8192"
            )
            .run(context -> {
                ModelRegistry registry = context.getBean(ModelRegistry.class);
                ModelDescriptor descriptor = openAiModel(registry, "gpt-5-mini");

                assertThat(descriptor.contextWindow()).isEqualTo(64_000);
                assertThat(registry.list())
                    .filteredOn(model -> model.provider().equals("openai"))
                    .extracting(ModelDescriptor::modelId)
                    .containsExactly("gpt-5-mini");
            });
    }

    @Test
    void appliesConfiguredDiscoveryDefaultsOnlyToMissingRemoteMetadata() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeAiAutoConfiguration.class)
            .withBean(RemoteModelDiscoveryClient.class, () -> new FixedRemoteModelDiscoveryClient(List.of(
                new DiscoveredModel(
                    "remote-explicit",
                    OptionalInt.of(128_000),
                    OptionalInt.of(16_384),
                    Optional.of(false),
                    Optional.of(false)
                ),
                DiscoveredModel.idOnly("remote-defaulted")
            )))
            .withPropertyValues(
                "lycode.ai.model-discovery.defaults.context-window=192000",
                "lycode.ai.model-discovery.defaults.max-output-tokens=12288",
                "lycode.ai.model-discovery.defaults.supports-thinking=true",
                "lycode.ai.model-discovery.defaults.supports-image-input=false",
                "lycode.ai.providers.fixture.enabled=true",
                "lycode.ai.providers.fixture.api-style=openai_compatible",
                "lycode.ai.providers.fixture.base-url=https://api.fixture.test/v1",
                "lycode.ai.providers.fixture.model-discovery.enabled=true"
            )
            .run(context -> {
                ModelRegistry registry = context.getBean(ModelRegistry.class);

                assertThat(model(registry, "fixture", "remote-explicit")).satisfies(descriptor -> {
                    assertThat(descriptor.contextWindow()).isEqualTo(128_000);
                    assertThat(descriptor.maxOutputTokens()).isEqualTo(16_384);
                    assertThat(descriptor.supportsThinking()).isFalse();
                    assertThat(descriptor.supportsImageInput()).isFalse();
                });
                assertThat(model(registry, "fixture", "remote-defaulted")).satisfies(descriptor -> {
                    assertThat(descriptor.contextWindow()).isEqualTo(192_000);
                    assertThat(descriptor.maxOutputTokens()).isEqualTo(12_288);
                    assertThat(descriptor.supportsThinking()).isTrue();
                    assertThat(descriptor.supportsImageInput()).isFalse();
                });
            });
    }

    @Test
    void passesConfiguredDiscoveryDefaultsToRuntimeProviderLogin() {
        Path home = tempDir.resolve("runtime-login-home");

        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeAiAutoConfiguration.class)
            .withBean(RemoteModelDiscoveryClient.class, () -> new FixedRemoteModelDiscoveryClient("runtime-model"))
            .withBean(LoginProviderPropertiesStore.class, () -> new LoginProviderPropertiesStore(home))
            .withPropertyValues(
                "lycode.ai.model-discovery.defaults.context-window=192000",
                "lycode.ai.model-discovery.defaults.max-output-tokens=12288",
                "lycode.ai.model-discovery.defaults.supports-thinking=true",
                "lycode.ai.model-discovery.defaults.supports-image-input=false",
                "lycode.ai.providers.openai.enabled=false"
            )
            .run(context -> {
                ProviderLoginResult result = context.getBean(ProviderLoginPort.class).register(
                    "zen",
                    "https://api.fixture.test/v1",
                    "fixture-key"
                );

                assertThat(result.models()).singleElement().satisfies(descriptor -> {
                    assertThat(descriptor.contextWindow()).isEqualTo(192_000);
                    assertThat(descriptor.maxOutputTokens()).isEqualTo(12_288);
                    assertThat(descriptor.supportsThinking()).isTrue();
                    assertThat(descriptor.supportsImageInput()).isFalse();
                });
            });
    }

    @Test
    void discoveredModelsAreAuthoritativeWhileMatchingLocalMetadataOverridesDefaults() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeAiAutoConfiguration.class)
            .withBean(RemoteModelDiscoveryClient.class, () -> new FixedRemoteModelDiscoveryClient(List.of(
                new DiscoveredModel(
                    "remote-a",
                    OptionalInt.of(128_000),
                    OptionalInt.of(16_384),
                    Optional.of(false),
                    Optional.of(false)
                )
            )))
            .withPropertyValues(
                "lycode.ai.providers.fixture.enabled=true",
                "lycode.ai.providers.fixture.api-style=openai_compatible",
                "lycode.ai.providers.fixture.base-url=https://api.fixture.test/v1",
                "lycode.ai.providers.fixture.model-discovery.enabled=true",
                "lycode.ai.providers.fixture.models[0].model-id=remote-a",
                "lycode.ai.providers.fixture.models[0].context-window=96000",
                "lycode.ai.providers.fixture.models[0].max-output-tokens=8192",
                "lycode.ai.providers.fixture.models[0].supports-thinking=true",
                "lycode.ai.providers.fixture.models[0].supports-image-input=true",
                "lycode.ai.providers.fixture.models[1].model-id=local-only",
                "lycode.ai.providers.fixture.models[1].context-window=64000",
                "lycode.ai.providers.fixture.models[1].max-output-tokens=4096"
            )
            .run(context -> {
                ModelRegistry registry = context.getBean(ModelRegistry.class);

                assertThat(registry.list())
                    .filteredOn(model -> model.provider().equals("fixture"))
                    .extracting(ModelDescriptor::modelId)
                    .containsExactly("remote-a");
                assertThat(model(registry, "fixture", "remote-a")).satisfies(descriptor -> {
                    assertThat(descriptor.contextWindow()).isEqualTo(96_000);
                    assertThat(descriptor.maxOutputTokens()).isEqualTo(8_192);
                    assertThat(descriptor.supportsThinking()).isTrue();
                    assertThat(descriptor.supportsImageInput()).isTrue();
                });
            });
    }

    @Test
    void rejectsNonPositiveDiscoveryDefaultTokenLimits() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeAiAutoConfiguration.class)
            .withPropertyValues("lycode.ai.model-discovery.defaults.context-window=0")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(rootCause(context.getStartupFailure()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Model discovery default token limits must be positive.");
            });
    }

    @Test
    void discoversEachProviderOnceWhileCreatingTheRegistry() {
        CountingRemoteModelDiscoveryClient discovery = new CountingRemoteModelDiscoveryClient("remote-a");

        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeAiAutoConfiguration.class)
            .withBean(RemoteModelDiscoveryClient.class, () -> discovery)
            .withPropertyValues(
                "lycode.ai.providers.fixture.enabled=true",
                "lycode.ai.providers.fixture.api-style=openai_compatible",
                "lycode.ai.providers.fixture.base-url=https://api.fixture.test/v1",
                "lycode.ai.providers.fixture.model-discovery.enabled=true"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(ModelRegistry.class);
                assertThat(discovery.calls()).isOne();
            });
    }

    @Test
    void failsStartupWhenRemoteModelDiscoveryIsUnavailable() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeAiAutoConfiguration.class)
            .withBean(RemoteModelDiscoveryClient.class, FailingRemoteModelDiscoveryClient::new)
            .withPropertyValues(
                "lycode.ai.providers.fixture.enabled=true",
                "lycode.ai.providers.fixture.api-style=openai_compatible",
                "lycode.ai.providers.fixture.base-url=https://api.fixture.test/v1",
                "lycode.ai.providers.fixture.model-discovery.enabled=true"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(rootCause(context.getStartupFailure()))
                    .isInstanceOfSatisfying(ModelProviderException.class, error ->
                        assertThat(error.errorId()).isEqualTo("model.discovery_unavailable"));
            });
    }

    @Test
    void configuresDiscoveredCompatibleProviderForChatCompletionsSseOnly() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeAiAutoConfiguration.class)
            .withBean(RemoteModelDiscoveryClient.class, () -> new FixedRemoteModelDiscoveryClient("remote-a"))
            .withPropertyValues(
                "lycode.ai.providers.fixture.enabled=true",
                "lycode.ai.providers.fixture.api-style=openai_compatible",
                "lycode.ai.providers.fixture.base-url=https://api.fixture.test/v1",
                "lycode.ai.providers.fixture.api-key=${LYCODE_FIXTURE_TOKEN}",
                "lycode.ai.providers.fixture.request-style=chat_completions",
                "lycode.ai.providers.fixture.fallback-request-style=chat_completions",
                "lycode.ai.providers.fixture.transport=sse",
                "lycode.ai.providers.fixture.model-discovery.enabled=true"
            )
            .run(context -> {
                List<?> adapters = context.getBean("openAiCompatibleProviderAdapters", List.class);
                OpenAiCompatibleProviderAdapter adapter = adapters.stream()
                    .map(OpenAiCompatibleProviderAdapter.class::cast)
                    .filter(candidate -> config(candidate).provider().equals("fixture"))
                    .findFirst()
                    .orElseThrow();

                assertThat(config(adapter).requestStyle()).isEqualTo(RequestStyle.CHAT_COMPLETIONS);
                assertThat(config(adapter).fallbackRequestStyle()).isEqualTo(RequestStyle.CHAT_COMPLETIONS);
                assertThat(config(adapter).transportMode()).isEqualTo(TransportMode.SSE);
            });
    }

    @Test
    void defaultsResponsesFallbackToResponses() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeAiAutoConfiguration.class)
            .withPropertyValues(
                "lycode.ai.providers.openai.enabled=true",
                "lycode.ai.providers.openai.base-url=https://api.openai.test/v1",
                "lycode.ai.providers.openai.api-key=${LYCODE_TEST_TOKEN}"
            )
            .run(context -> {
                LyCodeAiProperties properties = context.getBean(LyCodeAiProperties.class);

                assertThat(properties.getProviders().get("openai").getRequestStyle()).isEqualTo(RequestStyle.RESPONSES);
                assertThat(properties.getProviders().get("openai").getFallbackRequestStyle()).isEqualTo(RequestStyle.RESPONSES);
                assertThat(properties.getProviders().get("openai").getMaxRetries()).isEqualTo(3);
            });
    }

    @Test
    void bindsCompactionSummaryPropertiesWithoutSummaryModelOverride() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeAiAutoConfiguration.class)
            .withPropertyValues(
                "lycode.ai.compaction-summary.fallback-policy=skip_compaction"
            )
            .run(context -> {
                LyCodeAiProperties properties = context.getBean(LyCodeAiProperties.class);

                assertThat(properties.getCompactionSummary().getFallbackPolicy())
                    .isEqualTo(CompactionSummaryFallbackPolicy.SKIP_COMPACTION);
            });
    }

    @Test
    void createsAiCompactionSummarizerByDefault() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeAiAutoConfiguration.class)
            .run(context -> {
                assertThat(context).hasSingleBean(CompactionSummarizer.class);
                assertThat(context.getBean(CompactionSummarizer.class))
                    .isInstanceOf(AiCompactionSummarizer.class);
            });
    }

    @Test
    void createsAiCompactionSummarizerWhenFallbackPolicyConfigured() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeAiAutoConfiguration.class)
            .withPropertyValues("lycode.ai.compaction-summary.fallback-policy=skip_compaction")
            .run(context -> {
                assertThat(context).hasSingleBean(CompactionSummarizer.class);
                assertThat(context.getBean(CompactionSummarizer.class))
                    .isInstanceOf(AiCompactionSummarizer.class);
            });
    }

    @Test
    void supportsMultipleOpenAiCompatibleProvidersWithOneApiProvider() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeAiAutoConfiguration.class)
            .withPropertyValues(
                "lycode.ai.providers.openai.enabled=true",
                "lycode.ai.providers.openai.api-style=openai_compatible",
                "lycode.ai.providers.openai.base-url=https://api.openai.test/v1",
                "lycode.ai.providers.openai.api-key=${LYCODE_TEST_TOKEN}",
                "lycode.ai.providers.openai.models[0].model-id=gpt-5-mini",
                "lycode.ai.providers.openai.models[0].context-window=128000",
                "lycode.ai.providers.openai.models[0].max-output-tokens=16384",
                "lycode.ai.providers.fixture.enabled=true",
                "lycode.ai.providers.fixture.api-style=openai_compatible",
                "lycode.ai.providers.fixture.base-url=https://api.fixture.test/v1",
                "lycode.ai.providers.fixture.api-key=${LYCODE_FIXTURE_TOKEN}",
                "lycode.ai.providers.fixture.models[0].model-id=fixture-model",
                "lycode.ai.providers.fixture.models[0].context-window=64000",
                "lycode.ai.providers.fixture.models[0].max-output-tokens=8192"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(ApiProviderRegistry.class);
                assertThat(context.getBean("openAiCompatibleProviderAdapters", List.class)).hasSize(2);
                assertThat(context.getBean(ModelRegistry.class).list())
                    .extracting(descriptor -> descriptor.provider() + ":" + descriptor.modelId())
                    .contains("openai:gpt-5-mini", "fixture:fixture-model");
            });
    }

    @Test
    void supportsAnthropicProviderWithSeparateApiProvider() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeAiAutoConfiguration.class)
            .withPropertyValues(
                "lycode.ai.providers.openai.enabled=true",
                "lycode.ai.providers.openai.api-style=openai_compatible",
                "lycode.ai.providers.openai.base-url=https://api.openai.test/v1",
                "lycode.ai.providers.openai.api-key=${LYCODE_TEST_TOKEN}",
                "lycode.ai.providers.openai.models[0].model-id=gpt-5-mini",
                "lycode.ai.providers.openai.models[0].context-window=128000",
                "lycode.ai.providers.openai.models[0].max-output-tokens=16384",
                "lycode.ai.providers.anthropic.enabled=true",
                "lycode.ai.providers.anthropic.api-style=anthropic",
                "lycode.ai.providers.anthropic.base-url=https://api.anthropic.test/v1",
                "lycode.ai.providers.anthropic.api-key=${LYCODE_ANTHROPIC_TOKEN}",
                "lycode.ai.providers.anthropic.anthropic-version=2023-06-01",
                "lycode.ai.providers.anthropic.models[0].model-id=claude-sonnet-4-5",
                "lycode.ai.providers.anthropic.models[0].context-window=200000",
                "lycode.ai.providers.anthropic.models[0].max-output-tokens=64000",
                "lycode.ai.providers.anthropic.models[0].supports-thinking=true"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(ApiProviderRegistry.class);
                assertThat(context.getBean("openAiCompatibleProviderAdapters", List.class)).hasSize(1);
                List<?> anthropicAdapters = context.getBean("anthropicProviderAdapters", List.class);
                assertThat(anthropicAdapters).hasSize(1);
                assertThat(anthropicAdapters.getFirst()).isInstanceOf(AnthropicCompatibleProviderAdapter.class);
                AnthropicProviderConfig config = anthropicConfig((AnthropicCompatibleProviderAdapter) anthropicAdapters.getFirst());
                assertThat(config.baseUrl()).hasToString("https://api.anthropic.test/v1");
                assertThat(config.apiKey()).isEqualTo("${LYCODE_ANTHROPIC_TOKEN}");
                assertThat(config.anthropicVersion()).isEqualTo("2023-06-01");
                assertThat(context.getBean(ApiProviderRegistry.class).find(cn.lycode.contracts.model.ApiStyle.OPENAI_COMPATIBLE))
                    .isPresent();
                assertThat(context.getBean(ApiProviderRegistry.class).find(cn.lycode.contracts.model.ApiStyle.ANTHROPIC))
                    .isPresent();
                assertThat(context.getBean(ModelRegistry.class).list())
                    .filteredOn(descriptor -> descriptor.provider().equals("anthropic"))
                    .singleElement()
                    .satisfies(descriptor -> {
                        assertThat(descriptor.modelId()).isEqualTo("claude-sonnet-4-5");
                        assertThat(descriptor.apiStyle()).isEqualTo(cn.lycode.contracts.model.ApiStyle.ANTHROPIC);
                        assertThat(descriptor.contextWindow()).isEqualTo(200000);
                        assertThat(descriptor.supportsThinking()).isFalse();
                    });
            });
    }

    @Test
    void skipsRemoteModelDiscoveryForAnthropicProviders() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeAiAutoConfiguration.class)
            .withBean(RemoteModelDiscoveryClient.class, ThrowingRemoteModelDiscoveryClient::new)
            .withPropertyValues(
                "lycode.ai.providers.openai.enabled=false",
                "lycode.ai.providers.anthropic.enabled=true",
                "lycode.ai.providers.anthropic.api-style=anthropic",
                "lycode.ai.providers.anthropic.base-url=https://api.anthropic.test/v1",
                "lycode.ai.providers.anthropic.api-key=${LYCODE_ANTHROPIC_TOKEN}",
                "lycode.ai.providers.anthropic.model-discovery.enabled=true",
                "lycode.ai.providers.anthropic.model-discovery.paths[0]=/models",
                "lycode.ai.providers.anthropic.models[0].model-id=claude-sonnet-4-5",
                "lycode.ai.providers.anthropic.models[0].context-window=200000",
                "lycode.ai.providers.anthropic.models[0].max-output-tokens=64000"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(ModelRegistry.class);
                assertThat(context.getBean(ModelRegistry.class).list())
                    .extracting(ModelDescriptor::modelId)
                    .containsExactly("claude-sonnet-4-5");
            });
    }

    @Test
    void bindsProviderPropertiesFromYamlResources() {
        new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(LyCodeAiAutoConfiguration.class)
            .withPropertyValues("spring.config.name=application-test")
            .run(context -> {
                ModelRegistry registry = context.getBean(ModelRegistry.class);
                ModelDescriptor descriptor = openAiModel(registry, "gpt-5-mini");

                assertThat(registry.list())
                    .extracting(ModelDescriptor::modelId)
                    .contains("gpt-5.5", "gpt-5.4", "gpt-5.4-mini", "gpt-5-mini");
                assertThat(descriptor.provider()).isEqualTo("openai");
                assertThat(descriptor.baseUrl().toString()).isEqualTo("https://api.openai.test/v1");
                assertThat(descriptor.supportsThinking()).isTrue();
            });
    }

    private static final class ThrowingRemoteModelDiscoveryClient extends RemoteModelDiscoveryClient {
        @Override
        public List<DiscoveredModel> discoverModels(URI baseUrl, String apiKey, List<String> paths, Duration timeout) {
            throw new AssertionError("Remote discovery should not be called when disabled.");
        }
    }

    private static final class FixedRemoteModelDiscoveryClient extends RemoteModelDiscoveryClient {
        private final List<DiscoveredModel> models;

        private FixedRemoteModelDiscoveryClient(String modelId) {
            this(List.of(DiscoveredModel.idOnly(modelId)));
        }

        private FixedRemoteModelDiscoveryClient(List<DiscoveredModel> models) {
            this.models = List.copyOf(models);
        }

        @Override
        public List<DiscoveredModel> discoverModels(URI baseUrl, String apiKey, List<String> paths, Duration timeout) {
            return models;
        }
    }

    private static final class CountingRemoteModelDiscoveryClient extends RemoteModelDiscoveryClient {
        private final AtomicInteger calls = new AtomicInteger();
        private final String modelId;

        private CountingRemoteModelDiscoveryClient(String modelId) {
            this.modelId = modelId;
        }

        @Override
        public List<DiscoveredModel> discoverModels(URI baseUrl, String apiKey, List<String> paths, Duration timeout) {
            calls.incrementAndGet();
            return List.of(DiscoveredModel.idOnly(modelId));
        }

        private int calls() {
            return calls.get();
        }
    }

    private static final class FailingRemoteModelDiscoveryClient extends RemoteModelDiscoveryClient {
        @Override
        public List<DiscoveredModel> discoverModels(URI baseUrl, String apiKey, List<String> paths, Duration timeout) {
            throw new ModelProviderException(
                "model.discovery_unavailable",
                ErrorSeverity.ERROR,
                false,
                "Remote model discovery returned no usable models."
            );
        }
    }

    private static OpenAiProviderConfig config(OpenAiCompatibleProviderAdapter adapter) {
        try {
            Field field = OpenAiCompatibleProviderAdapter.class.getDeclaredField("config");
            field.setAccessible(true);
            return (OpenAiProviderConfig) field.get(adapter);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read OpenAI adapter config", e);
        }
    }

    private static AnthropicProviderConfig anthropicConfig(AnthropicCompatibleProviderAdapter adapter) {
        try {
            Field field = AnthropicCompatibleProviderAdapter.class.getDeclaredField("config");
            field.setAccessible(true);
            return (AnthropicProviderConfig) field.get(adapter);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read Anthropic adapter config", e);
        }
    }

    private static ModelDescriptor openAiModel(ModelRegistry registry, String modelId) {
        return model(registry, "openai", modelId);
    }

    private static ModelDescriptor model(ModelRegistry registry, String provider, String modelId) {
        return registry.list().stream()
            .filter(descriptor -> descriptor.provider().equals(provider))
            .filter(descriptor -> descriptor.modelId().equals(modelId))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing model: " + provider + "/" + modelId));
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current != null && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

}
