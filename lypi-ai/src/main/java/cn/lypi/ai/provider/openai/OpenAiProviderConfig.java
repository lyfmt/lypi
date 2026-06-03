package cn.lypi.ai.provider.openai;

import cn.lypi.ai.provider.RequestStyle;
import cn.lypi.ai.provider.TransportMode;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
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
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(websocketPath, "websocketPath");
        Objects.requireNonNull(apiKey, "apiKey");
        Objects.requireNonNull(requestStyle, "requestStyle");
        Objects.requireNonNull(fallbackRequestStyle, "fallbackRequestStyle");
        Objects.requireNonNull(transportMode, "transportMode");
        Objects.requireNonNull(timeout, "timeout");
        websocketUrl = websocketUrl == null ? Optional.empty() : websocketUrl;
        compat = compat == null ? Map.of() : Map.copyOf(compat);
    }
}
