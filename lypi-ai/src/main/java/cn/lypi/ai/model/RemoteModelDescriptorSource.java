package cn.lypi.ai.model;

import cn.lypi.contracts.model.ApiStyle;
import cn.lypi.contracts.model.CostProfile;
import cn.lypi.contracts.model.ModelDescriptor;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RemoteModelDescriptorSource implements ModelDescriptorSource {
    private final boolean enabled;
    private final String provider;
    private final URI baseUrl;
    private final ApiStyle apiStyle;
    private final String apiKey;
    private final List<String> discoveryPaths;
    private final Duration timeout;
    private final RemoteModelDiscoveryClient client;
    private final DescriptorDefaults defaults;

    public RemoteModelDescriptorSource(
        boolean enabled,
        String provider,
        URI baseUrl,
        ApiStyle apiStyle,
        String apiKey,
        List<String> discoveryPaths,
        Duration timeout,
        RemoteModelDiscoveryClient client,
        DescriptorDefaults defaults
    ) {
        this.enabled = enabled;
        this.provider = Objects.requireNonNull(provider, "provider");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.apiStyle = Objects.requireNonNull(apiStyle, "apiStyle");
        this.apiKey = apiKey;
        this.discoveryPaths = List.copyOf(Objects.requireNonNull(discoveryPaths, "discoveryPaths"));
        this.timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        this.client = Objects.requireNonNull(client, "client");
        this.defaults = Objects.requireNonNull(defaults, "defaults");
    }

    @Override
    public List<ModelDescriptor> list() {
        if (!enabled) {
            return List.of();
        }
        List<ModelDescriptor> descriptors = new ArrayList<>();
        for (String modelId : client.discover(baseUrl, apiKey, discoveryPaths, timeout)) {
            descriptors.add(new ModelDescriptor(
                provider,
                modelId,
                baseUrl,
                apiStyle,
                defaults.contextWindow(),
                defaults.maxOutputTokens(),
                defaults.supportsThinking(),
                defaults.supportsImageInput(),
                defaults.costProfile(),
                CompatSanitizer.sanitize(defaults.compat())
            ));
        }
        return descriptors;
    }

    public record DescriptorDefaults(
        int contextWindow,
        int maxOutputTokens,
        boolean supportsThinking,
        boolean supportsImageInput,
        CostProfile costProfile,
        Map<String, Object> compat
    ) {
        public DescriptorDefaults {
            Objects.requireNonNull(costProfile, "costProfile");
            compat = Map.copyOf(Objects.requireNonNull(compat, "compat"));
        }
    }
}
