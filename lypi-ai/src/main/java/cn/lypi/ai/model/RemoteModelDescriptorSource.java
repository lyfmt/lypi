package cn.lypi.ai.model;

import cn.lypi.contracts.model.ApiStyle;
import cn.lypi.contracts.model.ModelDescriptor;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

public final class RemoteModelDescriptorSource implements ModelDescriptorSource {
    private final boolean enabled;
    private final URI baseUrl;
    private final String apiKey;
    private final List<String> discoveryPaths;
    private final Duration timeout;
    private final RemoteModelDiscoveryClient client;
    private final DiscoveredModelDescriptorMapper mapper;

    public RemoteModelDescriptorSource(
        boolean enabled,
        String provider,
        URI baseUrl,
        ApiStyle apiStyle,
        String apiKey,
        List<String> discoveryPaths,
        Duration timeout,
        RemoteModelDiscoveryClient client,
        DiscoveredModelDefaults defaults
    ) {
        this.enabled = enabled;
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.apiKey = apiKey;
        this.discoveryPaths = List.copyOf(Objects.requireNonNull(discoveryPaths, "discoveryPaths"));
        this.timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        this.client = Objects.requireNonNull(client, "client");
        this.mapper = new DiscoveredModelDescriptorMapper(provider, this.baseUrl, apiStyle, defaults);
    }

    @Override
    public List<ModelDescriptor> list() {
        if (!enabled) {
            return List.of();
        }
        return client.discoverModels(baseUrl, apiKey, discoveryPaths, timeout).stream()
            .map(mapper::map)
            .toList();
    }
}
