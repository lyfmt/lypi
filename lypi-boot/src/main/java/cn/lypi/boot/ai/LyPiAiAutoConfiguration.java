package cn.lypi.boot.ai;

import cn.lypi.ai.ApiProvider;
import cn.lypi.ai.ApiProviderRegistry;
import cn.lypi.ai.DefaultApiProviderRegistry;
import cn.lypi.ai.DefaultModelPort;
import cn.lypi.ai.DefaultModelRegistry;
import cn.lypi.ai.ModelPort;
import cn.lypi.ai.ModelRegistry;
import cn.lypi.ai.ProviderAdapter;
import cn.lypi.ai.model.BuiltinModelDescriptorSource;
import cn.lypi.ai.model.CompositeModelDescriptorSource;
import cn.lypi.ai.model.ModelDescriptorSource;
import cn.lypi.ai.model.RemoteModelDescriptorSource;
import cn.lypi.ai.model.RemoteModelDiscoveryClient;
import cn.lypi.ai.model.StaticModelDescriptorSource;
import cn.lypi.ai.provider.openai.OpenAiCompatibleProviderAdapter;
import cn.lypi.ai.provider.openai.OpenAiProviderConfig;
import cn.lypi.ai.transport.HttpSseProviderTransport;
import cn.lypi.ai.transport.WebSocketProviderTransport;
import cn.lypi.boot.ai.LyPiAiProperties.ModelProperties;
import cn.lypi.boot.ai.LyPiAiProperties.ProviderProperties;
import cn.lypi.contracts.model.ApiStyle;
import cn.lypi.contracts.model.CostProfile;
import cn.lypi.contracts.model.ModelDescriptor;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LyPiAiProperties.class)
public class LyPiAiAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public ModelRegistry modelRegistry(LyPiAiProperties properties, RemoteModelDiscoveryClient discoveryClient) {
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
    public ApiProviderRegistry apiProviderRegistry(@Qualifier("openAiCompatibleProviderAdapters") List<ProviderAdapter> adapters) {
        return new DefaultApiProviderRegistry(adapters.stream()
            .filter(ApiProvider.class::isInstance)
            .map(ApiProvider.class::cast)
            .toList());
    }

    @Bean
    @ConditionalOnMissingBean(name = "openAiCompatibleProviderAdapters")
    public List<ProviderAdapter> openAiCompatibleProviderAdapters(LyPiAiProperties properties) {
        return List.copyOf(buildOpenAiProviderAdapters(properties));
    }

    @Bean
    @ConditionalOnMissingBean
    public RemoteModelDiscoveryClient remoteModelDiscoveryClient() {
        return new RemoteModelDiscoveryClient();
    }

    private ModelDescriptorSource modelDescriptorSource(LyPiAiProperties properties, RemoteModelDiscoveryClient discoveryClient) {
        List<ModelDescriptorSource> sources = new ArrayList<>();
        sources.add(new BuiltinModelDescriptorSource());
        sources.add(new StaticModelDescriptorSource(modelDescriptors(properties)));
        sources.add(new StaticModelDescriptorSource(remoteModelDescriptors(properties, discoveryClient)));
        return new CompositeModelDescriptorSource(sources);
    }

    private List<ModelDescriptor> modelDescriptors(LyPiAiProperties properties) {
        List<ModelDescriptor> descriptors = new ArrayList<>();
        properties.getProviders().forEach((providerName, provider) -> {
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

    private List<ModelDescriptor> remoteModelDescriptors(LyPiAiProperties properties, RemoteModelDiscoveryClient discoveryClient) {
        List<ModelDescriptor> descriptors = new ArrayList<>();
        properties.getProviders().forEach((providerName, provider) -> {
            if (!provider.isEnabled() || provider.getBaseUrl() == null || !provider.getModelDiscovery().isEnabled()) {
                return;
            }
            RemoteModelDescriptorSource.DescriptorDefaults defaults = descriptorDefaults(provider);
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

    private RemoteModelDescriptorSource.DescriptorDefaults descriptorDefaults(ProviderProperties provider) {
        ModelProperties firstModel = provider.getModels().isEmpty() ? new ModelProperties() : provider.getModels().getFirst();
        return new RemoteModelDescriptorSource.DescriptorDefaults(
            firstModel.getContextWindow(),
            firstModel.getMaxOutputTokens(),
            firstModel.isSupportsThinking(),
            firstModel.isSupportsImageInput(),
            new CostProfile(
                valueOrDefault(firstModel.getInputTokenCost(), BigDecimal.ZERO),
                valueOrDefault(firstModel.getOutputTokenCost(), BigDecimal.ZERO),
                valueOrDefault(firstModel.getCurrency(), "USD")
            ),
            sanitizedCompat(provider.getCompat(), firstModel.getCompat())
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
            model.isSupportsThinking(),
            model.isSupportsImageInput(),
            new CostProfile(
                valueOrDefault(model.getInputTokenCost(), BigDecimal.ZERO),
                valueOrDefault(model.getOutputTokenCost(), BigDecimal.ZERO),
                valueOrDefault(model.getCurrency(), "USD")
            ),
            sanitizedCompat(provider.getCompat(), model.getCompat())
        );
    }

    private List<OpenAiCompatibleProviderAdapter> buildOpenAiProviderAdapters(LyPiAiProperties properties) {
        List<OpenAiCompatibleProviderAdapter> adapters = new ArrayList<>();
        properties.getProviders().forEach((providerName, provider) -> {
            if (!supportsOpenAiAdapter(provider)) {
                return;
            }
            adapters.add(openAiProviderAdapter(openAiConfig(providerName, provider)));
        });
        return adapters;
    }

    private OpenAiCompatibleProviderAdapter openAiProviderAdapter(OpenAiProviderConfig config) {
        return new OpenAiCompatibleProviderAdapter(
            config,
            new WebSocketProviderTransport(),
            new HttpSseProviderTransport(),
            new HttpSseProviderTransport()
        );
    }

    private boolean supportsOpenAiAdapter(ProviderProperties provider) {
        return provider.isEnabled() && provider.getApiStyle() == ApiStyle.OPENAI_COMPATIBLE && provider.getBaseUrl() != null;
    }

    private OpenAiProviderConfig openAiConfig(String providerName, ProviderProperties provider) {
        return new OpenAiProviderConfig(
            providerName,
            provider.getBaseUrl(),
            Optional.ofNullable(provider.getWebsocketUrl()),
            valueOrDefault(provider.getWebsocketPath(), "/v1/responses"),
            provider.getApiKey(),
            provider.getRequestStyle(),
            provider.getFallbackRequestStyle(),
            provider.getTransport(),
            valueOrDefault(provider.getTimeout(), Duration.ofSeconds(30)),
            provider.getMaxRetries(),
            sanitizedCompat(provider.getCompat(), Map.of())
        );
    }

    private Map<String, Object> sanitizedCompat(Map<String, Object> providerCompat, Map<String, Object> modelCompat) {
        Map<String, Object> compat = new LinkedHashMap<>();
        compat.putAll(providerCompat);
        compat.putAll(modelCompat);
        compat.keySet().removeIf(this::sensitiveCompatKey);
        return compat;
    }

    private boolean sensitiveCompatKey(String key) {
        String normalized = key.replace("-", "").replace("_", "").toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "apikey", "authorization", "accesstoken", "bearertoken", "token" -> true;
            default -> false;
        };
    }

    private static <T> T valueOrDefault(T value, T defaultValue) {
        return value == null ? defaultValue : value;
    }
}
