package cn.lypi.ai.provider.anthropic;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

public record AnthropicProviderConfig(
    String provider,
    URI baseUrl,
    String apiKey,
    String anthropicVersion,
    Duration timeout,
    int maxRetries,
    Map<String, Object> compat
) {
    public AnthropicProviderConfig {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(apiKey, "apiKey");
        Objects.requireNonNull(anthropicVersion, "anthropicVersion");
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        compat = compat == null ? Map.of() : Map.copyOf(compat);
    }
}
