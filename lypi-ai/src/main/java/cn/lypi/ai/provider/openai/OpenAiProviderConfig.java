package cn.lypi.ai.provider.openai;

import cn.lypi.ai.provider.RequestStyle;
import cn.lypi.ai.provider.TransportMode;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

public record OpenAiProviderConfig(
    String provider,
    URI baseUrl,
    Optional<URI> websocketUrl,
    String websocketPath,
    String apiKey,
    RequestStyle requestStyle,
    RequestStyle fallbackRequestStyle,
    TransportMode transportMode,
    Duration timeout,
    int maxRetries,
    Map<String, Object> compat
) {
    public OpenAiProviderConfig {
        websocketUrl = websocketUrl == null ? Optional.empty() : websocketUrl;
        compat = Map.copyOf(compat);
    }
}
