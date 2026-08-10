package cn.lypi.boot.ai;

import cn.lypi.ai.ApiProviderRegistry;
import cn.lypi.ai.DefaultApiProviderRegistry;
import cn.lypi.ai.DefaultModelPort;
import cn.lypi.ai.DefaultModelRegistry;
import cn.lypi.ai.ModelPort;
import cn.lypi.ai.ModelRegistry;
import cn.lypi.ai.ProviderAdapter;
import cn.lypi.ai.ProviderAdapterApiProvider;
import cn.lypi.ai.RuntimeModelRegistry;
import cn.lypi.ai.model.BuiltinModelDescriptorSource;
import cn.lypi.ai.model.CompatSanitizer;
import cn.lypi.ai.model.CompositeModelDescriptorSource;
import cn.lypi.ai.model.DiscoveredModelDefaults;
import cn.lypi.ai.model.ModelDescriptorSource;
import cn.lypi.ai.model.RemoteModelDescriptorSource;
import cn.lypi.ai.model.RemoteModelDiscoveryClient;
import cn.lypi.ai.model.StaticModelDescriptorSource;
import cn.lypi.ai.provider.RequestStyle;
import cn.lypi.ai.provider.TransportMode;
import cn.lypi.ai.provider.anthropic.AnthropicCompatibleProviderAdapter;
import cn.lypi.ai.provider.anthropic.AnthropicProviderConfig;
import cn.lypi.ai.provider.openai.OpenAiCompatibleProviderAdapter;
import cn.lypi.ai.provider.openai.OpenAiProviderConfig;
import cn.lypi.agent.compact.AiCompactionSummarizer;
import cn.lypi.agent.compact.CompactSummaryContextBuilder;
import cn.lypi.agent.compact.CompactSummaryInstructionFactory;
import cn.lypi.agent.compact.CompactionSummarizer;
import cn.lypi.agent.compact.CompactionSummaryOptions;
import cn.lypi.ai.transport.HttpSseProviderTransport;
import cn.lypi.ai.transport.WebSocketProviderTransport;
import cn.lypi.boot.ai.LyPiAiProperties.ModelProperties;
import cn.lypi.boot.ai.LyPiAiProperties.ProviderProperties;
import cn.lypi.contracts.model.ApiStyle;
import cn.lypi.contracts.model.CostProfile;
import cn.lypi.contracts.model.ModelDescriptor;
import cn.lypi.contracts.runtime.ProviderLoginPort;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(LyPiAiProperties.class)
public class LyPiAiAutoConfiguration {
    private static final String BUILTIN_OPENAI_PROVIDER = "openai";
    private static final String OPENAI_API_KEY_ENV = "OPENAI_API_KEY";
    private static final String BUILTIN_OPENAI_WEBSOCKET_PATH = "/v1/responses";
    private static final Duration BUILTIN_OPENAI_TIMEOUT = Duration.ofSeconds(30);

    @Bean
    @ConditionalOnMissingBean(ModelRegistry.class)
    public RuntimeModelRegistry modelRegistry(LyPiAiProperties properties, RemoteModelDiscoveryClient discoveryClient) {
        return new DefaultModelRegistry(modelDescriptorSource(properties, discoveryClient).list());
    }

    @Bean
    @ConditionalOnMissingBean
    public ModelPort modelPort(
        ModelRegistry modelRegistry,
        ApiProviderRegistry apiProviderRegistry
    ) {
        return new DefaultModelPort(modelRegistry, apiProviderRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public ApiProviderRegistry apiProviderRegistry(
        @Qualifier("openAiCompatibleApiProvider") ProviderAdapterApiProvider openAiCompatibleApiProvider,
        @Qualifier("anthropicProviderAdapters") List<ProviderAdapter> anthropicAdapters
    ) {
        List<ProviderAdapterApiProvider> providers = new ArrayList<>();
        providers.add(openAiCompatibleApiProvider);
        if (!anthropicAdapters.isEmpty()) {
            providers.add(new ProviderAdapterApiProvider(ApiStyle.ANTHROPIC, anthropicAdapters));
        }
        return new DefaultApiProviderRegistry(providers);
    }

    @Bean(name = "openAiCompatibleApiProvider")
    @ConditionalOnMissingBean(name = "openAiCompatibleApiProvider")
    public ProviderAdapterApiProvider openAiCompatibleApiProvider(
        @Qualifier("openAiCompatibleProviderAdapters") List<ProviderAdapter> openAiAdapters
    ) {
        return new ProviderAdapterApiProvider(ApiStyle.OPENAI_COMPATIBLE, openAiAdapters);
    }

    @Bean
    @ConditionalOnMissingBean(name = "openAiCompatibleProviderAdapters")
    public List<ProviderAdapter> openAiCompatibleProviderAdapters(LyPiAiProperties properties) {
        return List.copyOf(buildOpenAiProviderAdapters(properties));
    }

    @Bean
    @ConditionalOnMissingBean(name = "anthropicProviderAdapters")
    public List<ProviderAdapter> anthropicProviderAdapters(LyPiAiProperties properties) {
        return List.copyOf(buildAnthropicProviderAdapters(properties));
    }

    @Bean
    @ConditionalOnMissingBean
    public RemoteModelDiscoveryClient remoteModelDiscoveryClient() {
        return new RemoteModelDiscoveryClient();
    }

    @Bean
    @ConditionalOnMissingBean
    public LoginProviderPropertiesStore loginProviderPropertiesStore() {
        return new LoginProviderPropertiesStore(Path.of(System.getProperty("user.home", ".")));
    }

    @Bean
    @ConditionalOnMissingBean(ProviderLoginPort.class)
    public ProviderLoginPort providerLoginPort(
        LyPiAiProperties properties,
        RemoteModelDiscoveryClient discoveryClient,
        ObjectProvider<RuntimeModelRegistry> modelRegistry,
        @Qualifier("openAiCompatibleApiProvider") ObjectProvider<ProviderAdapterApiProvider> openAiDispatcher,
        LoginProviderPropertiesStore propertiesStore
    ) {
        RuntimeModelRegistry runtimeModelRegistry = modelRegistry.getIfAvailable();
        ProviderAdapterApiProvider dispatcher = openAiDispatcher.getIfAvailable();
        if (runtimeModelRegistry == null || dispatcher == null) {
            return ProviderLoginPort.unavailable();
        }
        return new OpenAiCompatibleProviderLoginService(
            discoveryClient,
            runtimeModelRegistry,
            dispatcher,
            propertiesStore,
            descriptorDefaults(properties)
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public CompactionSummarizer compactionSummarizer(ModelPort modelPort, LyPiAiProperties properties) {
        LyPiAiProperties.CompactionSummaryProperties summary = properties.getCompactionSummary();
        return new AiCompactionSummarizer(
            modelPort,
            new CompactSummaryContextBuilder(new CompactSummaryInstructionFactory()),
            new CompactionSummaryOptions(summary.getFallbackPolicy())
        );
    }

    private ModelDescriptorSource modelDescriptorSource(LyPiAiProperties properties, RemoteModelDiscoveryClient discoveryClient) {
        DiscoveredModelDefaults defaults = descriptorDefaults(properties);
        List<ModelDescriptor> remote = remoteModelDescriptors(properties, discoveryClient, defaults);
        Set<ModelKey> discovered = remote.stream()
            .map(model -> new ModelKey(model.provider(), model.modelId()))
            .collect(Collectors.toUnmodifiableSet());
        List<ModelDescriptor> builtin = authoritativeLocalDescriptors(
            properties,
            builtinModelDescriptors(properties),
            discovered
        );
        List<ModelDescriptor> configured = authoritativeLocalDescriptors(
            properties,
            modelDescriptors(properties),
            discovered
        );
        return new CompositeModelDescriptorSource(List.of(
            new StaticModelDescriptorSource(remote),
            new StaticModelDescriptorSource(builtin),
            new StaticModelDescriptorSource(configured)
        ));
    }

    private List<ModelDescriptor> authoritativeLocalDescriptors(
        LyPiAiProperties properties,
        List<ModelDescriptor> local,
        Set<ModelKey> discovered
    ) {
        Map<String, ProviderProperties> providers = effectiveProviders(properties);
        return local.stream()
            .filter(descriptor -> {
                ProviderProperties provider = providers.get(descriptor.provider());
                return !usesRemoteModelDiscovery(provider)
                    || discovered.contains(new ModelKey(descriptor.provider(), descriptor.modelId()));
            })
            .toList();
    }

    private boolean usesRemoteModelDiscovery(ProviderProperties provider) {
        return provider != null
            && provider.isEnabled()
            && provider.getBaseUrl() != null
            && valueOrDefault(provider.getApiStyle(), ApiStyle.OPENAI_COMPATIBLE) == ApiStyle.OPENAI_COMPATIBLE
            && provider.getModelDiscovery().isEnabled();
    }

    private List<ModelDescriptor> builtinModelDescriptors(LyPiAiProperties properties) {
        if (isBuiltinOpenAiDisabled(properties)) {
            return List.of();
        }
        ProviderProperties provider = mergedBuiltinOpenAiProvider(properties.getProviders().get(BUILTIN_OPENAI_PROVIDER));
        return new BuiltinModelDescriptorSource().list().stream()
            .map(descriptor -> withProviderOverrides(descriptor, provider))
            .toList();
    }

    private boolean isBuiltinOpenAiDisabled(LyPiAiProperties properties) {
        ProviderProperties provider = properties.getProviders().get(BUILTIN_OPENAI_PROVIDER);
        return provider != null && provider.isEnabledConfigured() && !provider.isEnabled();
    }

    private List<ModelDescriptor> modelDescriptors(LyPiAiProperties properties) {
        List<ModelDescriptor> descriptors = new ArrayList<>();
        effectiveProviders(properties).forEach((providerName, provider) -> {
            if (!provider.isEnabled() || provider.getBaseUrl() == null) {
                return;
            }
            for (ModelProperties model : provider.getModels()) {
                if (model.getModelId() == null || model.getModelId().isBlank()) {
                    continue;
                }
                descriptors.add(modelDescriptor(providerName, provider, model));
            }
        });
        return descriptors;
    }

    private List<ModelDescriptor> remoteModelDescriptors(
        LyPiAiProperties properties,
        RemoteModelDiscoveryClient discoveryClient,
        DiscoveredModelDefaults defaults
    ) {
        List<ModelDescriptor> descriptors = new ArrayList<>();
        effectiveProviders(properties).forEach((providerName, provider) -> {
            if (!provider.isEnabled() || provider.getBaseUrl() == null || !provider.getModelDiscovery().isEnabled()) {
                return;
            }
            if (valueOrDefault(provider.getApiStyle(), ApiStyle.OPENAI_COMPATIBLE) != ApiStyle.OPENAI_COMPATIBLE) {
                return;
            }
            descriptors.addAll(new RemoteModelDescriptorSource(
                true,
                providerName,
                provider.getBaseUrl(),
                valueOrDefault(provider.getApiStyle(), ApiStyle.OPENAI_COMPATIBLE),
                provider.getApiKey(),
                provider.getModelDiscovery().getPaths(),
                valueOrDefault(provider.getTimeout(), Duration.ofSeconds(30)),
                discoveryClient,
                defaults
            ).list());
        });
        return descriptors;
    }

    private DiscoveredModelDefaults descriptorDefaults(LyPiAiProperties properties) {
        LyPiAiProperties.ModelDefaultsProperties defaults = properties.getModelDiscovery().getDefaults();
        if (defaults.getContextWindow() <= 0 || defaults.getMaxOutputTokens() <= 0) {
            throw new IllegalArgumentException("Model discovery default token limits must be positive.");
        }
        return new DiscoveredModelDefaults(
            defaults.getContextWindow(),
            defaults.getMaxOutputTokens(),
            defaults.isSupportsThinking(),
            defaults.isSupportsImageInput(),
            new CostProfile(BigDecimal.ZERO, BigDecimal.ZERO, "USD"),
            Map.of()
        );
    }

    private ModelDescriptor modelDescriptor(String providerName, ProviderProperties provider, ModelProperties model) {
        return new ModelDescriptor(
            providerName,
            model.getModelId(),
            provider.getBaseUrl(),
            valueOrDefault(provider.getApiStyle(), ApiStyle.OPENAI_COMPATIBLE),
            model.getContextWindow(),
            model.getMaxOutputTokens(),
            supportsThinking(provider, model),
            model.isSupportsImageInput(),
            new CostProfile(
                valueOrDefault(model.getInputTokenCost(), BigDecimal.ZERO),
                valueOrDefault(model.getOutputTokenCost(), BigDecimal.ZERO),
                valueOrDefault(model.getCurrency(), "USD")
            ),
            sanitizedCompat(provider.getCompat(), model.getCompat())
        );
    }

    private boolean supportsThinking(ProviderProperties provider, ModelProperties model) {
        return provider.getApiStyle() != ApiStyle.ANTHROPIC && model.isSupportsThinking();
    }

    private List<OpenAiCompatibleProviderAdapter> buildOpenAiProviderAdapters(LyPiAiProperties properties) {
        Map<String, OpenAiCompatibleProviderAdapter> adapters = new LinkedHashMap<>();
        effectiveProviders(properties).forEach((providerName, provider) -> {
            if (!supportsOpenAiAdapter(provider)) {
                return;
            }
            adapters.put(providerName, openAiProviderAdapter(openAiConfig(providerName, provider)));
        });
        return new ArrayList<>(adapters.values());
    }

    private Map<String, ProviderProperties> effectiveProviders(LyPiAiProperties properties) {
        Map<String, ProviderProperties> providers = new LinkedHashMap<>();
        if (!isBuiltinOpenAiDisabled(properties)) {
            providers.put(BUILTIN_OPENAI_PROVIDER, mergedBuiltinOpenAiProvider(properties.getProviders().get(BUILTIN_OPENAI_PROVIDER)));
        }
        properties.getProviders().forEach((providerName, provider) -> {
            if (BUILTIN_OPENAI_PROVIDER.equals(providerName)) {
                return;
            }
            providers.put(providerName, provider);
        });
        return providers;
    }

    private ProviderProperties mergedBuiltinOpenAiProvider(ProviderProperties configured) {
        ProviderProperties provider = builtinOpenAiProvider();
        if (configured == null) {
            return provider;
        }
        copyProviderOverrides(provider, configured);
        return provider;
    }

    private ProviderProperties builtinOpenAiProvider() {
        ProviderProperties provider = new ProviderProperties();
        provider.setEnabled(true);
        provider.setApiStyle(ApiStyle.OPENAI_COMPATIBLE);
        provider.setRequestStyle(RequestStyle.RESPONSES);
        provider.setFallbackRequestStyle(RequestStyle.CHAT_COMPLETIONS);
        provider.setTransport(TransportMode.AUTO);
        provider.setBaseUrl(java.net.URI.create("https://api.openai.com/v1"));
        provider.setWebsocketPath(BUILTIN_OPENAI_WEBSOCKET_PATH);
        provider.setApiKey(environmentValue(OPENAI_API_KEY_ENV));
        provider.setTimeout(BUILTIN_OPENAI_TIMEOUT);
        provider.setMaxRetries(3);
        return provider;
    }

    private void copyProviderOverrides(ProviderProperties target, ProviderProperties source) {
        if (source.isEnabledConfigured()) {
            target.setEnabled(source.isEnabled());
        }
        if (source.isApiStyleConfigured()) {
            target.setApiStyle(source.getApiStyle());
        }
        if (source.isRequestStyleConfigured()) {
            target.setRequestStyle(source.getRequestStyle());
        }
        if (source.isFallbackRequestStyleConfigured()) {
            target.setFallbackRequestStyle(source.getFallbackRequestStyle());
        }
        if (source.isTransportConfigured()) {
            target.setTransport(source.getTransport());
        }
        if (source.isBaseUrlConfigured()) {
            target.setBaseUrl(source.getBaseUrl());
        }
        if (source.isWebsocketPathConfigured()) {
            target.setWebsocketPath(source.getWebsocketPath());
        }
        if (source.isWebsocketUrlConfigured()) {
            target.setWebsocketUrl(source.getWebsocketUrl());
        }
        if (source.isApiKeyConfigured()) {
            target.setApiKey(source.getApiKey());
        }
        if (source.isAnthropicVersionConfigured()) {
            target.setAnthropicVersion(source.getAnthropicVersion());
        }
        if (source.isTimeoutConfigured()) {
            target.setTimeout(source.getTimeout());
        }
        if (source.isMaxRetriesConfigured()) {
            target.setMaxRetries(source.getMaxRetries());
        }
        target.setCompat(source.getCompat());
        target.setModelDiscovery(source.getModelDiscovery());
        target.setModels(source.getModels());
    }

    private String environmentValue(String name) {
        return Optional.ofNullable(System.getenv(name)).orElse("");
    }

    private OpenAiCompatibleProviderAdapter openAiProviderAdapter(OpenAiProviderConfig config) {
        return new OpenAiCompatibleProviderAdapter(
            config,
            new WebSocketProviderTransport(),
            new HttpSseProviderTransport(),
            new HttpSseProviderTransport()
        );
    }

    private List<AnthropicCompatibleProviderAdapter> buildAnthropicProviderAdapters(LyPiAiProperties properties) {
        Map<String, AnthropicCompatibleProviderAdapter> adapters = new LinkedHashMap<>();
        effectiveProviders(properties).forEach((providerName, provider) -> {
            if (!supportsAnthropicAdapter(provider)) {
                return;
            }
            adapters.put(providerName, anthropicProviderAdapter(anthropicConfig(providerName, provider)));
        });
        return new ArrayList<>(adapters.values());
    }

    private AnthropicCompatibleProviderAdapter anthropicProviderAdapter(AnthropicProviderConfig config) {
        return new AnthropicCompatibleProviderAdapter(
            config,
            new HttpSseProviderTransport()
        );
    }

    private boolean supportsOpenAiAdapter(ProviderProperties provider) {
        return provider.isEnabled() && provider.getApiStyle() == ApiStyle.OPENAI_COMPATIBLE && provider.getBaseUrl() != null;
    }

    private boolean supportsAnthropicAdapter(ProviderProperties provider) {
        return provider.isEnabled() && provider.getApiStyle() == ApiStyle.ANTHROPIC && provider.getBaseUrl() != null;
    }

    private OpenAiProviderConfig openAiConfig(String providerName, ProviderProperties provider) {
        return new OpenAiProviderConfig(
            providerName,
            provider.getBaseUrl(),
            Optional.ofNullable(provider.getWebsocketUrl()),
            valueOrDefault(provider.getWebsocketPath(), "/v1/responses"),
            valueOrDefault(provider.getApiKey(), ""),
            provider.getRequestStyle(),
            provider.getFallbackRequestStyle(),
            provider.getTransport(),
            valueOrDefault(provider.getTimeout(), Duration.ofSeconds(30)),
            provider.getMaxRetries(),
            sanitizedCompat(provider.getCompat(), Map.of())
        );
    }

    private AnthropicProviderConfig anthropicConfig(String providerName, ProviderProperties provider) {
        return new AnthropicProviderConfig(
            providerName,
            provider.getBaseUrl(),
            valueOrDefault(provider.getApiKey(), ""),
            valueOrDefault(provider.getAnthropicVersion(), "2023-06-01"),
            valueOrDefault(provider.getTimeout(), Duration.ofSeconds(30)),
            provider.getMaxRetries(),
            sanitizedCompat(provider.getCompat(), Map.of())
        );
    }

    private Map<String, Object> sanitizedCompat(Map<String, Object> providerCompat, Map<String, Object> modelCompat) {
        Map<String, Object> compat = new LinkedHashMap<>();
        compat.putAll(providerCompat);
        compat.putAll(modelCompat);
        return CompatSanitizer.sanitize(compat);
    }

    private ModelDescriptor withProviderOverrides(ModelDescriptor descriptor, ProviderProperties provider) {
        return new ModelDescriptor(
            descriptor.provider(),
            descriptor.modelId(),
            provider.getBaseUrl(),
            valueOrDefault(provider.getApiStyle(), descriptor.apiStyle()),
            descriptor.contextWindow(),
            descriptor.maxOutputTokens(),
            descriptor.supportsThinking(),
            descriptor.supportsImageInput(),
            descriptor.costProfile(),
            sanitizedCompat(provider.getCompat(), descriptor.compat())
        );
    }

    private static <T> T valueOrDefault(T value, T defaultValue) {
        return value == null ? defaultValue : value;
    }

    private record ModelKey(String provider, String modelId) {
    }
}
