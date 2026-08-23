package cn.lycode.ai.model;

import cn.lycode.contracts.model.ApiStyle;
import cn.lycode.contracts.model.ModelDescriptor;
import java.net.URI;
import java.util.Objects;

public final class DiscoveredModelDescriptorMapper {
    private final String provider;
    private final URI baseUrl;
    private final ApiStyle apiStyle;
    private final DiscoveredModelDefaults defaults;

    public DiscoveredModelDescriptorMapper(
        String provider,
        URI baseUrl,
        ApiStyle apiStyle,
        DiscoveredModelDefaults defaults
    ) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.apiStyle = Objects.requireNonNull(apiStyle, "apiStyle");
        this.defaults = Objects.requireNonNull(defaults, "defaults");
    }

    public ModelDescriptor map(DiscoveredModel discovered) {
        Objects.requireNonNull(discovered, "discovered");
        return new ModelDescriptor(
            provider,
            discovered.modelId(),
            baseUrl,
            apiStyle,
            discovered.contextWindow().orElse(defaults.contextWindow()),
            discovered.maxOutputTokens().orElse(defaults.maxOutputTokens()),
            discovered.supportsThinking().orElse(defaults.supportsThinking()),
            discovered.supportsImageInput().orElse(defaults.supportsImageInput()),
            defaults.costProfile(),
            CompatSanitizer.sanitize(defaults.compat())
        );
    }
}
